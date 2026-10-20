// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2019-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.convertwithmoss.format.teenage.op1;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.mossgrabers.convertwithmoss.core.IMultisampleSource;
import de.mossgrabers.convertwithmoss.core.INotifier;
import de.mossgrabers.convertwithmoss.core.SafeFileNames;
import de.mossgrabers.convertwithmoss.core.creator.AbstractCreator;
import de.mossgrabers.convertwithmoss.core.creator.DestinationAudioFormat;
import de.mossgrabers.convertwithmoss.core.model.ISampleData;
import de.mossgrabers.convertwithmoss.core.model.ISampleLoop;
import de.mossgrabers.convertwithmoss.core.model.ISampleZone;
import de.mossgrabers.convertwithmoss.core.settings.EmptySettingsUI;
import de.mossgrabers.convertwithmoss.file.AudioFileUtils;
import de.mossgrabers.convertwithmoss.file.StreamUtils;
import de.mossgrabers.convertwithmoss.file.iff.IffFile;
import de.mossgrabers.convertwithmoss.file.wav.WaveFile;
import de.mossgrabers.convertwithmoss.format.TagDetector;
import de.mossgrabers.tools.ui.Functions;


/**
 * Creator for Teenage Engineering OP-1 Field drum kits. A drum kit is an AIFF file which contains
 * the sounds of its 24 keys one after the other and the parameters of the keys. The zones of a
 * multi-sample are laid out chromatically across the keys: each key plays the zone of its note
 * transposed to the note of the key. Each sample is stored only once, all keys which play it share
 * it. A drum kit with one zone per key keeps the order of its keys.
 *
 * @author Jürgen Moßgraber
 */
public class Op1FieldCreator extends AbstractCreator<EmptySettingsUI>
{
    /** The device plays 16 bit / 44.1 kHz samples. */
    private static final DestinationAudioFormat DESTINATION_FORMAT = new DestinationAudioFormat (new int []
    {
        16
    }, Op1FieldTag.SAMPLE_RATE, true);

    /** The keys without a zone play a short silence. */
    private static final int                    SILENCE_FRAMES     = 64;
    /** The length of the fade which ends a shortened sound to prevent a click. */
    private static final int                    FADE_FRAMES        = 441;
    private static final String                 COMPRESSION_TYPE   = "sowt";
    private static final String                 COMPRESSION_NAME   = "Signed integer (little-endian) linear PCM";
    /** The categories of drum kits whose keys keep their order. */
    private static final Set<String>            DRUM_CATEGORIES    = Set.of (TagDetector.CATEGORY_ACOUSTIC_DRUM, TagDetector.CATEGORY_DRUM, TagDetector.CATEGORY_PERCUSSION, TagDetector.CATEGORY_KICK, TagDetector.CATEGORY_SNARE, TagDetector.CATEGORY_HI_HAT, TagDetector.CATEGORY_CLAP);
    /** The order in which the octaves are tested to fit a multi-sample onto the keys. */
    private static final int []                 OCTAVES            =
    {
        0,
        -1,
        1,
        -2,
        2,
        -3,
        3,
        -4,
        4
    };

    private final ObjectMapper                  mapper             = new ObjectMapper ();


    /**
     * Constructor.
     *
     * @param notifier The notifier
     */
    public Op1FieldCreator (final INotifier notifier)
    {
        super ("Teenage Engineering OP-1 Field", "OP1Field", notifier, EmptySettingsUI.INSTANCE);
    }


    /** {@inheritDoc} */
    @Override
    public void createPreset (final File destinationFolder, final IMultisampleSource multisampleSource) throws IOException
    {
        final List<ISampleZone> zones = new ArrayList<> ();
        for (final ISampleZone zone: multisampleSource.getAllSampleZones (true))
            if (zone.getSampleData ().isPresent ())
                zones.add (zone);
        if (zones.isEmpty ())
        {
            this.notifier.logError ("IDS_OP1_NO_SOUNDS", multisampleSource.getName ());
            return;
        }

        // The positions of the zones need to match the sample rate of the device
        this.recalculateSamplePositions (multisampleSource, Op1FieldTag.SAMPLE_RATE);

        final ISampleZone [] keyZones = new ISampleZone [Op1FieldTag.NUMBER_OF_KEYS];
        final double [] keySemitones = new double [Op1FieldTag.NUMBER_OF_KEYS];
        final int firstNote = mapKeys (zones, DRUM_CATEGORIES.contains (multisampleSource.getMetadata ().getCategory ()), keyZones, keySemitones);
        if (Arrays.stream (keyZones).allMatch (Objects::isNull))
        {
            this.notifier.logError ("IDS_OP1_NO_SOUNDS", multisampleSource.getName ());
            return;
        }

        final File file = this.createUniqueFilename (destinationFolder, SafeFileNames.create (multisampleSource.getName ()), "aif");
        this.notifier.log ("IDS_NOTIFY_STORING", file.getAbsolutePath ());
        if (firstNote != Op1FieldTag.LOWEST_NOTE)
            this.notifier.log ("IDS_OP1_KEYS_MOVED", Integer.toString (firstNote), Integer.toString (firstNote + Op1FieldTag.NUMBER_OF_KEYS - 1));

        // Each sample is stored once, in the order of the keys which play it, with the part which
        // these keys play. Samples with the same audio are stored once as well, e.g. if several
        // zones play parts of the same file
        final Map<ISampleData, Sound> soundsBySample = new IdentityHashMap<> ();
        final List<Sound> sounds = new ArrayList<> ();
        final Set<ISampleZone> playedZones = Collections.newSetFromMap (new IdentityHashMap<> ());
        for (final ISampleZone zone: keyZones)
        {
            if (zone == null || !playedZones.add (zone))
                continue;
            final ISampleData sampleData = zone.getSampleData ().get ();
            Sound sound = soundsBySample.get (sampleData);
            if (sound == null)
            {
                this.progress.notifyProgress ();
                sound = this.convertSound (zone);
                final Sound sameSound = findSameSound (sounds, sound);
                if (sameSound == null)
                    sounds.add (sound);
                else
                    sound = sameSound;
                soundsBySample.put (sampleData, sound);
            }
            sound.zones.add (zone);
        }
        if (playedZones.size () < zones.size ())
            this.notifier.log ("IDS_OP1_ZONES_NOT_PLAYED", Integer.toString (zones.size () - playedZones.size ()), Integer.toString (zones.size ()));

        final int channels = this.fitSounds (sounds);
        final ByteArrayOutputStream audio = new ByteArrayOutputStream ();
        for (final Sound sound: sounds)
        {
            sound.position = audio.size () / (channels * 2);
            audio.write (createSoundAudio (sound, channels));
        }
        final int silencePosition = audio.size () / (channels * 2);
        audio.write (new byte [SILENCE_FRAMES * channels * 2]);

        final String patch = this.createPatch (multisampleSource, keyZones, keySemitones, soundsBySample, channels, silencePosition);
        writeDrumKit (file, patch, audio.toByteArray (), channels);

        this.progress.notifyDone ();
    }


    /**
     * Map the zones onto the keys. The keys of a drum kit with one zone per key keep their order,
     * the lowest 24 are used. A multi-sample is laid out chromatically, moved by octaves until its
     * zones cover as many keys as possible.
     *
     * @param zones The zones
     * @param isDrumCategory True if the category of the multi-sample is one of a drum kit
     * @param keyZones Where to store the zone which each key plays, null if it plays none
     * @param keySemitones Where to store the transposition of each key in semi-tones
     * @return The note of the zones which the lowest key plays
     */
    private static int mapKeys (final List<ISampleZone> zones, final boolean isDrumCategory, final ISampleZone [] keyZones, final double [] keySemitones)
    {
        final int numKeys = Op1FieldTag.NUMBER_OF_KEYS;
        boolean isDrumKit = isDrumCategory;
        int lowestNote = 127;
        int highestNote = 0;
        for (final ISampleZone zone: zones)
        {
            final int keyLow = getKeyLow (zone);
            final int keyHigh = getKeyHigh (zone);
            if (keyLow != keyHigh)
                isDrumKit = false;
            lowestNote = Math.min (lowestNote, keyLow);
            highestNote = Math.max (highestNote, keyHigh);
        }

        int firstNote = Op1FieldTag.LOWEST_NOTE;
        if (isDrumKit)
        {
            if (lowestNote < Op1FieldTag.LOWEST_NOTE || highestNote >= Op1FieldTag.LOWEST_NOTE + numKeys)
                firstNote = Math.min (lowestNote, 128 - numKeys);
        }
        else
        {
            int maxCoverage = -1;
            for (final int octave: OCTAVES)
            {
                final int note = Op1FieldTag.LOWEST_NOTE + 12 * octave;
                if (note < 0 || note + numKeys > 128)
                    continue;
                int coverage = 0;
                for (int key = 0; key < numKeys; key++)
                    if (findZone (zones, note + key) != null)
                        coverage++;
                if (coverage > maxCoverage)
                {
                    maxCoverage = coverage;
                    firstNote = note;
                }
            }
        }

        for (int key = 0; key < numKeys; key++)
        {
            final int note = firstNote + key;
            final ISampleZone zone = findZone (zones, note);
            keyZones[key] = zone;
            if (zone != null)
            {
                final int keyRoot = limitToDefault (zone.getKeyRoot (), getKeyLow (zone));
                keySemitones[key] = (note - keyRoot) * zone.getKeyTracking () + zone.getTuning ();
            }
        }
        return firstNote;
    }


    /**
     * Find the zone which plays a note. If several zones play it, the one which plays at the
     * highest velocity is chosen since the keys of the device are not velocity sensitive.
     *
     * @param zones The zones
     * @param note The note
     * @return The zone or null if no zone plays the note
     */
    private static ISampleZone findZone (final List<ISampleZone> zones, final int note)
    {
        ISampleZone result = null;
        for (final ISampleZone zone: zones)
            if (note >= getKeyLow (zone) && note <= getKeyHigh (zone) && (result == null || limitToDefault (zone.getVelocityHigh (), 127) > limitToDefault (result.getVelocityHigh (), 127)))
                result = zone;
        return result;
    }


    /**
     * Convert the sample of a zone into the format of the device.
     *
     * @param zone The zone
     * @return The sound with the converted sample
     * @throws IOException Could not convert the sample
     */
    private Sound convertSound (final ISampleZone zone) throws IOException
    {
        this.logResampling (zone, DESTINATION_FORMAT);
        final WaveFile waveFile = AudioFileUtils.convertToWav (zone.getSampleData ().get (), DESTINATION_FORMAT);
        if (waveFile.getDataChunk () == null)
            throw new IOException (Functions.getMessage ("IDS_WAV_CONVERSION_FAILED", zone.getName ()));
        final int channels = waveFile.getFormatChunk ().getNumberOfChannels ();
        if (channels > 2)
            throw new IOException (Functions.getMessage ("IDS_NOTIFY_ERR_MONO", Integer.toString (channels), zone.getName ()));
        return new Sound (waveFile.getDataChunk ().getData (), channels);
    }


    /**
     * Find a sound with the same audio.
     *
     * @param sounds The sounds to search
     * @param sound The sound to find
     * @return The sound with the same audio or null if there is none
     */
    private static Sound findSameSound (final List<Sound> sounds, final Sound sound)
    {
        for (final Sound other: sounds)
            if (other.channels == sound.channels && other.hash == sound.hash && Arrays.equals (other.audio, sound.audio))
                return other;
        return null;
    }


    /**
     * Calculate the part of each sound which is stored and shorten the sounds if they do not fit
     * into the drum kit. A drum kit with mono sounds holds less, therefore it is stored in stereo if
     * the sounds do not fit. If they are still too long, the longest sounds are shortened to the
     * same length.
     *
     * @param sounds The sounds
     * @return The number of channels of the drum kit
     */
    private int fitSounds (final List<Sound> sounds)
    {
        int channels = 1;
        long totalFrames = 0;
        for (final Sound sound: sounds)
        {
            if (sound.channels == 2)
                channels = 2;
            final int [] range = getRange (sound);
            sound.start = range[0];
            sound.frames = range[1] - range[0];
            totalFrames += sound.frames;
        }

        if (channels == 1 && totalFrames > getMaxFrames (1))
        {
            this.notifier.log ("IDS_OP1_STEREO_FOR_LENGTH", Integer.toString (Op1FieldTag.MAX_SECONDS_MONO), Integer.toString (Op1FieldTag.MAX_SECONDS_STEREO));
            channels = 2;
        }

        final long maxFrames = getMaxFrames (channels);
        if (totalFrames > maxFrames)
        {
            final int maxLength = getMaxLength (sounds, maxFrames);
            int shortened = 0;
            for (final Sound sound: sounds)
                if (sound.frames > maxLength)
                {
                    // A reversed sound is played from its end, therefore its start is removed
                    sound.isCutAtStart = sound.zones.stream ().allMatch (ISampleZone::isReversed);
                    if (sound.isCutAtStart)
                        sound.start += sound.frames - maxLength;
                    sound.frames = maxLength;
                    sound.isShortened = true;
                    shortened++;
                }
            this.notifier.log ("IDS_OP1_SOUNDS_SHORTENED", Integer.toString (Op1FieldTag.getMaxSeconds (channels)), Integer.toString (shortened), formatDouble (maxLength / (double) Op1FieldTag.SAMPLE_RATE, 2));
            if (sounds.size () > 1)
                this.notifier.log ("IDS_OP1_REDUCE_SAMPLES");
        }

        return channels;
    }


    /**
     * Get the length to which the longest sounds need to be shortened so that all sounds fit into
     * the given number of frames.
     *
     * @param sounds The sounds
     * @param maxFrames The number of frames which the drum kit holds
     * @return The length in frames
     */
    private static int getMaxLength (final List<Sound> sounds, final long maxFrames)
    {
        final int [] lengths = sounds.stream ().mapToInt (sound -> sound.frames).sorted ().toArray ();
        long remainingFrames = maxFrames;
        for (int i = 0; i < lengths.length; i++)
        {
            final long share = remainingFrames / (lengths.length - i);
            if (lengths[i] > share)
                return (int) share;
            remainingFrames -= lengths[i];
        }
        return Integer.MAX_VALUE;
    }


    /**
     * Create the audio of the stored part of a sound in the format of the drum kit.
     *
     * @param sound The sound
     * @param channels The number of channels of the drum kit
     * @return The interleaved 16-bit little-endian audio
     */
    private static byte [] createSoundAudio (final Sound sound, final int channels)
    {
        final byte [] audio;
        if (sound.channels == channels)
            audio = Arrays.copyOfRange (sound.audio, sound.start * channels * 2, (sound.start + sound.frames) * channels * 2);
        else
        {
            // A mono sound plays on both channels of a stereo drum kit
            audio = new byte [sound.frames * 4];
            for (int i = 0; i < sound.frames; i++)
            {
                final int offset = (sound.start + i) * 2;
                audio[i * 4] = sound.audio[offset];
                audio[i * 4 + 1] = sound.audio[offset + 1];
                audio[i * 4 + 2] = sound.audio[offset];
                audio[i * 4 + 3] = sound.audio[offset + 1];
            }
        }

        if (sound.isShortened)
            fade (audio, channels, sound.isCutAtStart);
        return audio;
    }


    /**
     * Create the parameters of the drum kit.
     *
     * @param multisampleSource The multi-sample source
     * @param keyZones The zone which each key plays, null if it plays none
     * @param keySemitones The transposition of each key in semi-tones
     * @param soundsBySample The sound of each sample
     * @param channels The number of channels of the drum kit
     * @param silencePosition The position of the silence which the keys without a zone play
     * @return The JSON document
     * @throws IOException Could not create the document
     */
    private String createPatch (final IMultisampleSource multisampleSource, final ISampleZone [] keyZones, final double [] keySemitones, final Map<ISampleData, Sound> soundsBySample, final int channels, final int silencePosition) throws IOException
    {
        final int numKeys = Op1FieldTag.NUMBER_OF_KEYS;
        final double positionsPerFrame = Op1FieldTag.getPositionsPerFrame (channels, Op1FieldTag.SAMPLE_RATE);
        final int [] starts = new int [numKeys];
        final int [] ends = new int [numKeys];
        final int [] pitches = new int [numKeys];
        final int [] volumes = new int [numKeys];
        final int [] pans = new int [numKeys];
        final int [] playModes = new int [numKeys];
        final int [] directions = new int [numKeys];
        for (int key = 0; key < numKeys; key++)
        {
            // The part of the stored sound which the key plays, it might have been shortened
            final ISampleZone zone = keyZones[key];
            final Sound sound = zone == null ? null : soundsBySample.get (zone.getSampleData ().get ());
            final int [] range = sound == null ? null : getRange (zone, sound.numFrames);
            final int start = sound == null ? 0 : Math.clamp (range[0] - sound.start, 0, sound.frames);
            final int end = sound == null ? 0 : Math.clamp (range[1] - sound.start, start, sound.frames);
            if (end <= start)
            {
                starts[key] = toPosition (silencePosition, positionsPerFrame);
                ends[key] = toPosition (silencePosition + SILENCE_FRAMES, positionsPerFrame);
                volumes[key] = Op1FieldTag.DEFAULT_VOLUME;
                pans[key] = Op1FieldTag.CENTER_VALUE;
                playModes[key] = Op1FieldTag.PLAYMODE_GATE;
                directions[key] = Op1FieldTag.DIRECTION_FORWARD;
                continue;
            }

            starts[key] = toPosition (sound.position + start, positionsPerFrame);
            ends[key] = toPosition (sound.position + end, positionsPerFrame);
            pitches[key] = Op1FieldTag.semitonesToPitch (keySemitones[key]);
            volumes[key] = Op1FieldTag.gainToVolume (zone.getGain ());
            pans[key] = Op1FieldTag.panningToPan (zone.getPanning ());
            playModes[key] = getPlayMode (zone, range, end - start);
            directions[key] = zone.isReversed () ? Op1FieldTag.DIRECTION_REVERSE : Op1FieldTag.DIRECTION_FORWARD;
        }

        final Date creationDateTime = multisampleSource.getMetadata ().getCreationDateTime ();
        final long modificationTime = (creationDateTime == null ? new Date () : creationDateTime).getTime () / 1000;

        // The device reads the parameters only in alphabetical order. The effect and the LFO are
        // the ones of a new drum kit, inactive
        final Map<String, Object> patch = new TreeMap<> ();
        patch.put (Op1FieldTag.TAG_ATTACK, new int [numKeys]);
        patch.put (Op1FieldTag.TAG_DRUM_VERSION, Integer.valueOf (Op1FieldTag.DRUM_VERSION));
        patch.put (Op1FieldTag.TAG_DYNAMIC_ENVELOPE, new int []
        {
            0,
            8192,
            0,
            8192,
            0,
            0,
            0,
            0
        });
        patch.put (Op1FieldTag.TAG_END, ends);
        patch.put (Op1FieldTag.TAG_FX_ACTIVE, Boolean.FALSE);
        final int [] fxParameters = new int [8];
        Arrays.fill (fxParameters, 8000);
        patch.put (Op1FieldTag.TAG_FX_PARAMS, fxParameters);
        patch.put (Op1FieldTag.TAG_FX_TYPE, "delay");
        patch.put (Op1FieldTag.TAG_LFO_ACTIVE, Boolean.FALSE);
        patch.put (Op1FieldTag.TAG_LFO_PARAMS, new int []
        {
            16000,
            16000,
            16000,
            16000,
            0,
            0,
            0,
            0
        });
        patch.put (Op1FieldTag.TAG_LFO_TYPE, "tremolo");
        patch.put (Op1FieldTag.TAG_MODIFICATION_TIME, Long.valueOf (modificationTime));
        patch.put (Op1FieldTag.TAG_NAME, multisampleSource.getName ());
        patch.put (Op1FieldTag.TAG_OCTAVE, Integer.valueOf (0));
        patch.put (Op1FieldTag.TAG_PAN, pans);
        patch.put (Op1FieldTag.TAG_PAN_AB, new boolean [numKeys]);
        patch.put (Op1FieldTag.TAG_PITCH, pitches);
        patch.put (Op1FieldTag.TAG_PLAYMODE, playModes);
        patch.put (Op1FieldTag.TAG_REVERSE, directions);
        patch.put (Op1FieldTag.TAG_START, starts);
        patch.put (Op1FieldTag.TAG_STEREO, Boolean.valueOf (channels == 2));
        patch.put (Op1FieldTag.TAG_TYPE, Op1FieldTag.TYPE_DRUM);
        patch.put (Op1FieldTag.TAG_VOLUME, volumes);
        return this.mapper.writeValueAsString (patch);
    }


    /**
     * Get the play mode of a key. A loop across the whole part repeats it while the key is
     * pressed, a one-shot plays the whole part, all others play while the key is pressed.
     *
     * @param zone The zone which the key plays
     * @param range The start and end of the played part of the sample of the zone
     * @param frames The number of frames which the key plays, less if the sound was shortened
     * @return The play mode
     */
    private static int getPlayMode (final ISampleZone zone, final int [] range, final int frames)
    {
        final List<ISampleLoop> loops = zone.getLoops ();
        final int length = range[1] - range[0];
        if (!loops.isEmpty () && frames == length)
        {
            final ISampleLoop loop = loops.get (0);
            final int end = range[1] - 1;
            final int loopEnd = loop.getEnd () < 0 ? end : loop.getEnd ();
            final int tolerance = Math.max (2, length / 100);
            if (loop.getStart () - range[0] <= tolerance && end - loopEnd <= tolerance)
                return Op1FieldTag.PLAYMODE_LOOP;
        }
        return zone.isOneShot () ? Op1FieldTag.PLAYMODE_ONE_SHOT : Op1FieldTag.PLAYMODE_GATE;
    }


    /**
     * Write the drum kit as an AIFC file with little-endian sound data like the device does.
     *
     * @param file The file to write
     * @param patch The parameters of the drum kit
     * @param audio The interleaved 16-bit little-endian audio
     * @param channels The number of channels of the audio
     * @throws IOException Could not write the file
     */
    private static void writeDrumKit (final File file, final String patch, final byte [] audio, final int channels) throws IOException
    {
        // The JSON document is followed by a line feed and padded with spaces
        final byte [] json = patch.getBytes (StandardCharsets.UTF_8);
        if (json.length + 1 > Op1FieldTag.DRUM_JSON_SIZE)
            throw new IOException (Functions.getMessage ("IDS_OP1_PARAMETERS_TOO_LARGE"));
        final byte [] applicationData = new byte [4 + Op1FieldTag.DRUM_JSON_SIZE];
        Arrays.fill (applicationData, (byte) ' ');
        System.arraycopy (Op1FieldTag.APPLICATION_SIGNATURE.getBytes (StandardCharsets.US_ASCII), 0, applicationData, 0, 4);
        System.arraycopy (json, 0, applicationData, 4, json.length);
        applicationData[4 + json.length] = '\n';

        final ByteArrayOutputStream versionData = new ByteArrayOutputStream ();
        StreamUtils.writeUnsigned32 (versionData, Op1FieldTag.AIFC_VERSION, true);

        final ByteArrayOutputStream commonData = new ByteArrayOutputStream ();
        StreamUtils.writeUnsigned16 (commonData, channels, true);
        StreamUtils.writeUnsigned32 (commonData, audio.length / (channels * 2L), true);
        StreamUtils.writeUnsigned16 (commonData, 16, true);
        commonData.write (toExtended (Op1FieldTag.SAMPLE_RATE));
        StreamUtils.writeAscii (commonData, COMPRESSION_TYPE, 4);
        // The name of the compression is a Pascal string, padded to an even length
        commonData.write (COMPRESSION_NAME.length ());
        StreamUtils.writeAscii (commonData, COMPRESSION_NAME, COMPRESSION_NAME.length ());
        if (COMPRESSION_NAME.length () % 2 == 0)
            commonData.write (0);

        // Offset and block size of the sound data are not used
        final ByteArrayOutputStream soundData = new ByteArrayOutputStream (8 + audio.length);
        soundData.write (new byte [8]);
        soundData.write (audio);

        final ByteArrayOutputStream content = new ByteArrayOutputStream ();
        IffFile.writeLocalChunk (content, "FVER", versionData.toByteArray ());
        IffFile.writeLocalChunk (content, "COMM", commonData.toByteArray ());
        IffFile.writeLocalChunk (content, "APPL", applicationData);
        IffFile.writeLocalChunk (content, "SSND", soundData.toByteArray ());
        try (final OutputStream out = new FileOutputStream (file))
        {
            IffFile.writeGroupChunk (out, IffFile.FORM, "AIFC", content.toByteArray ());
        }
    }


    /**
     * Fade a sound in or out to prevent a click where it was shortened.
     *
     * @param audio The interleaved 16-bit little-endian audio
     * @param channels The number of channels
     * @param atStart Fade in at the start if true, otherwise fade out at the end
     */
    private static void fade (final byte [] audio, final int channels, final boolean atStart)
    {
        final int frames = audio.length / (channels * 2);
        final int fadeFrames = Math.min (FADE_FRAMES, frames);
        for (int i = 0; i < fadeFrames; i++)
        {
            final int frame = atStart ? i : frames - 1 - i;
            final double factor = i / (double) fadeFrames;
            for (int channel = 0; channel < channels; channel++)
            {
                final int offset = (frame * channels + channel) * 2;
                final int value = (int) Math.round ((short) (audio[offset] & 0xFF | audio[offset + 1] << 8) * factor);
                audio[offset] = (byte) value;
                audio[offset + 1] = (byte) (value >> 8);
            }
        }
    }


    /**
     * Get the start and end of the played part of the sample of a zone.
     *
     * @param zone The zone
     * @param numFrames The number of frames of its sample
     * @return The start and the (exclusive) end
     */
    private static int [] getRange (final ISampleZone zone, final int numFrames)
    {
        final int start = Math.clamp (limitToDefault (zone.getStart (), 0), 0, numFrames);
        final int stop = zone.getStop () <= 0 ? numFrames : Math.clamp (zone.getStop (), start, numFrames);
        return new int []
        {
            start,
            stop
        };
    }


    /**
     * Get the start and end of the part of a sample which all zones of a sound play.
     *
     * @param sound The sound
     * @return The start and the (exclusive) end
     */
    private static int [] getRange (final Sound sound)
    {
        final int [] result =
        {
            sound.numFrames,
            0
        };
        for (final ISampleZone zone: sound.zones)
        {
            final int [] range = getRange (zone, sound.numFrames);
            result[0] = Math.min (result[0], range[0]);
            result[1] = Math.max (result[1], range[1]);
        }
        return result;
    }


    private static long getMaxFrames (final int channels)
    {
        return (long) Op1FieldTag.getMaxSeconds (channels) * Op1FieldTag.SAMPLE_RATE - SILENCE_FRAMES;
    }


    private static int toPosition (final int frame, final double positionsPerFrame)
    {
        return (int) Math.min (Math.floor (frame * positionsPerFrame), Op1FieldTag.MAX_POSITION);
    }


    private static int getKeyLow (final ISampleZone zone)
    {
        return Math.clamp (limitToDefault (zone.getKeyLow (), 0), 0, 127);
    }


    private static int getKeyHigh (final ISampleZone zone)
    {
        return Math.clamp (limitToDefault (zone.getKeyHigh (), 127), getKeyLow (zone), 127);
    }


    /**
     * Convert a positive integer into an 80-bit IEEE 754 extended precision number.
     *
     * @param value The value
     * @return The 10 bytes of the number
     */
    private static byte [] toExtended (final int value)
    {
        final byte [] result = new byte [10];
        long mantissa = value;
        int exponent = 16383 + 31;
        while ((mantissa & 0x80000000L) == 0)
        {
            mantissa <<= 1;
            exponent--;
        }
        result[0] = (byte) (exponent >> 8);
        result[1] = (byte) exponent;
        for (int i = 0; i < 4; i++)
            result[2 + i] = (byte) (mantissa >> (24 - 8 * i));
        return result;
    }


    /** A sample in the format of the device and the part of it which the drum kit stores. */
    private static class Sound
    {
        private final byte []           audio;
        private final int               channels;
        private final int               numFrames;
        private final int               hash;
        private final List<ISampleZone> zones        = new ArrayList<> ();
        /** The start of the stored part in the sample. */
        private int                     start;
        /** The number of frames of the stored part. */
        private int                     frames;
        /** The position of the stored part in the drum kit. */
        private int                     position;
        private boolean                 isShortened  = false;
        private boolean                 isCutAtStart = false;


        /**
         * Constructor.
         *
         * @param audio The interleaved 16-bit little-endian audio
         * @param channels The number of channels
         */
        Sound (final byte [] audio, final int channels)
        {
            this.audio = audio;
            this.channels = channels;
            this.numFrames = audio.length / (channels * 2);
            this.hash = Arrays.hashCode (audio);
        }
    }
}
