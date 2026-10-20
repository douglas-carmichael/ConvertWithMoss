// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2019-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.convertwithmoss.format.teenage.op1;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.mossgrabers.convertwithmoss.core.IMultisampleSource;
import de.mossgrabers.convertwithmoss.core.INotifier;
import de.mossgrabers.convertwithmoss.core.detector.AbstractDetector;
import de.mossgrabers.convertwithmoss.core.model.IAudioMetadata;
import de.mossgrabers.convertwithmoss.core.model.ISampleLoop;
import de.mossgrabers.convertwithmoss.core.model.ISampleZone;
import de.mossgrabers.convertwithmoss.core.model.enumeration.LoopType;
import de.mossgrabers.convertwithmoss.core.model.implementation.DefaultAudioMetadata;
import de.mossgrabers.convertwithmoss.core.model.implementation.DefaultGroup;
import de.mossgrabers.convertwithmoss.core.model.implementation.DefaultSampleLoop;
import de.mossgrabers.convertwithmoss.core.model.implementation.DefaultSampleZone;
import de.mossgrabers.convertwithmoss.core.model.implementation.InMemorySampleData;
import de.mossgrabers.convertwithmoss.core.settings.MetadataSettingsUI;
import de.mossgrabers.convertwithmoss.exception.ParseException;
import de.mossgrabers.convertwithmoss.file.aiff.AiffFileSampleData;
import de.mossgrabers.convertwithmoss.file.wav.FormatChunk;
import de.mossgrabers.convertwithmoss.file.wav.WaveFile;
import de.mossgrabers.tools.FileUtils;
import de.mossgrabers.tools.ui.Functions;


/**
 * Detects Teenage Engineering OP-1 Field and OP-1 patches. A patch is an AIFF file whose
 * application chunk contains the parameters of the patch. Drum kits and the patches of the sampler
 * synthesizer engine are read, the patches of the other synthesizer engines contain no samples.
 *
 * @author Jürgen Moßgraber
 */
public class Op1FieldDetector extends AbstractDetector<MetadataSettingsUI>
{
    /** The range of the play mode values is split into 4 equal parts, one for each mode. */
    private static final int   PLAYMODE_RANGE = 8192;

    private final ObjectMapper mapper         = new ObjectMapper ();


    /**
     * Constructor.
     *
     * @param notifier The notifier
     */
    public Op1FieldDetector (final INotifier notifier)
    {
        super ("Teenage Engineering OP-1 Field", "OP1Field", notifier, new MetadataSettingsUI ("OP1Field"), ".aif", ".aiff");
    }


    /** {@inheritDoc} */
    @Override
    protected List<IMultisampleSource> readPresetFile (final File file)
    {
        if (this.waitForDelivery ())
            return Collections.emptyList ();

        try
        {
            final AiffFileSampleData sampleData = new AiffFileSampleData (file);
            final Optional<JsonNode> patch = this.readPatch (sampleData);
            if (patch.isEmpty ())
            {
                this.notifier.logError ("IDS_OP1_NOT_A_PATCH", file.getAbsolutePath ());
                return Collections.emptyList ();
            }

            final JsonNode root = patch.get ();
            final String name = FileUtils.getNameWithoutType (file);
            final String type = getText (root, Op1FieldTag.TAG_TYPE);
            final List<ISampleZone> zones;
            if (Op1FieldTag.TYPE_DRUM.equals (type))
                zones = readDrumKit (name, root, sampleData);
            else if (Op1FieldTag.TYPE_SAMPLER.equals (type))
                zones = readSampler (name, root, sampleData);
            else
            {
                this.notifier.logError ("IDS_OP1_NO_SAMPLE_PATCH", type, file.getAbsolutePath ());
                return Collections.emptyList ();
            }

            if (zones.isEmpty ())
            {
                this.notifier.logError ("IDS_OP1_NO_SOUNDS", file.getAbsolutePath ());
                return Collections.emptyList ();
            }

            final IMultisampleSource multisampleSource = this.createMultisampleSource (file, name, Collections.singletonList (new DefaultGroup (zones)));
            final JsonNode modificationTime = root.get (Op1FieldTag.TAG_MODIFICATION_TIME);
            if (modificationTime != null && modificationTime.isNumber () && modificationTime.asDouble () > 0)
                multisampleSource.getMetadata ().setCreationDateTime (new Date (Math.round (modificationTime.asDouble () * 1000.0)));
            return Collections.singletonList (multisampleSource);
        }
        catch (final IOException ex)
        {
            this.notifier.logError ("IDS_NOTIFY_ERR_LOAD_FILE", ex);
            return Collections.emptyList ();
        }
    }


    /**
     * Read the parameters of the patch from the application chunk of the AIFF file.
     *
     * @param sampleData The AIFF file
     * @return The parameters or empty if the file is not a patch
     * @throws IOException Could not read the file or parse the parameters
     */
    private Optional<JsonNode> readPatch (final AiffFileSampleData sampleData) throws IOException
    {
        final Optional<byte []> data = sampleData.getAiffFile ().getApplicationData (Op1FieldTag.APPLICATION_SIGNATURE);
        if (data.isEmpty ())
            return Optional.empty ();

        // The device pads the JSON document with spaces, some tools use zeros
        final byte [] content = data.get ();
        int length = 0;
        while (length < content.length && content[length] != 0)
            length++;
        final JsonNode root = this.mapper.readTree (new String (content, 0, length, StandardCharsets.UTF_8).trim ());
        return root != null && root.isObject () ? Optional.of (root) : Optional.empty ();
    }


    /**
     * Read a drum kit. Each of the 24 keys plays a part of the sample, transposed by the pitch of
     * the key. Each key becomes a sample zone which is mapped to its key. Keys which play nearly the
     * same part of the sample share it.
     *
     * @param name The name of the patch
     * @param root The parameters of the patch
     * @param sampleData The AIFF file
     * @return The zones
     * @throws IOException Could not read the audio
     */
    private static List<ISampleZone> readDrumKit (final String name, final JsonNode root, final AiffFileSampleData sampleData) throws IOException
    {
        final WaveFile waveFile = convertToWav (sampleData);
        final FormatChunk formatChunk = waveFile.getFormatChunk ();
        final int channels = formatChunk.getNumberOfChannels ();
        final int sampleRate = formatChunk.getSampleRate ();
        final int bitsPerSample = formatChunk.getSignificantBitsPerSample ();
        if (formatChunk.getCompressionCode () != FormatChunk.WAVE_FORMAT_PCM)
            throw new IOException (Functions.getMessage ("IDS_ERR_SOURCE_FORMAT_NOT_SUPPORTED", name));
        final int bytesPerFrame = formatChunk.calculateBytesPerSample ();
        final byte [] audio = waveFile.getDataChunk ().getData ();
        final int numFrames = audio.length / bytesPerFrame;
        final double positionsPerFrame = Op1FieldTag.getPositionsPerFrame (channels, sampleRate);

        final int numKeys = Op1FieldTag.NUMBER_OF_KEYS;
        final int [] starts = getValues (root, Op1FieldTag.TAG_START, numKeys, 0);
        final int [] ends = getValues (root, Op1FieldTag.TAG_END, numKeys, 0);
        final int [] pitches = getValues (root, Op1FieldTag.TAG_PITCH, numKeys, 0);
        final int [] volumes = getValues (root, Op1FieldTag.TAG_VOLUME, numKeys, Op1FieldTag.DEFAULT_VOLUME);
        final int [] pans = getValues (root, Op1FieldTag.TAG_PAN, numKeys, Op1FieldTag.CENTER_VALUE);
        final int [] playModes = getValues (root, Op1FieldTag.TAG_PLAYMODE, numKeys, Op1FieldTag.PLAYMODE_ONE_SHOT);
        final int [] directions = getValues (root, Op1FieldTag.TAG_REVERSE, numKeys, Op1FieldTag.DIRECTION_FORWARD);
        final boolean [] blends = getFlags (root, Op1FieldTag.TAG_PAN_AB, numKeys);

        final int [] keyStarts = new int [numKeys];
        final int [] keyEnds = new int [numKeys];
        final int [] groups = new int [numKeys];
        for (int key = 0; key < numKeys; key++)
        {
            keyStarts[key] = Math.clamp (Math.round (starts[key] / positionsPerFrame), 0, numFrames);
            keyEnds[key] = Math.clamp (Math.round (ends[key] / positionsPerFrame), keyStarts[key], numFrames);
            // A key without sound or with a volume of zero is silent
            if (volumes[key] <= 0 || isSilent (audio, keyStarts[key] * bytesPerFrame, keyEnds[key] * bytesPerFrame))
                keyEnds[key] = keyStarts[key];
            // In A/B mode the two channels contain two different sounds and the panning blends
            // between them, which creates a part of its own
            if (blends[key] && (channels != 2 || bitsPerSample != 16))
                blends[key] = false;
            groups[key] = key;
        }

        // Keys which play nearly the same part of the sample share it, e.g. if several keys play
        // the same sound with a different pitch
        for (int key = 0; key < numKeys; key++)
            for (int other = 0; other < key; other++)
                if (!blends[key] && !blends[other] && isSamePart (keyStarts, keyEnds, key, other))
                    groups[findGroup (groups, key)] = findGroup (groups, other);
        final int [] groupStarts = new int [numKeys];
        final int [] groupEnds = new int [numKeys];
        Arrays.fill (groupStarts, numFrames);
        for (int key = 0; key < numKeys; key++)
        {
            final int group = findGroup (groups, key);
            groupStarts[group] = Math.min (groupStarts[group], keyStarts[key]);
            groupEnds[group] = Math.max (groupEnds[group], keyEnds[key]);
        }

        final InMemorySampleData [] groupData = new InMemorySampleData [numKeys];
        final List<ISampleZone> zones = new ArrayList<> ();
        for (int key = 0; key < numKeys; key++)
        {
            final int frames = keyEnds[key] - keyStarts[key];
            if (frames <= 0)
                continue;

            final InMemorySampleData data;
            final int offset;
            double panning = Op1FieldTag.panToPanning (pans[key]);
            if (blends[key])
            {
                final byte [] part = blendChannels (Arrays.copyOfRange (audio, keyStarts[key] * bytesPerFrame, keyEnds[key] * bytesPerFrame), Math.clamp (pans[key] / (double) Op1FieldTag.MAX_VALUE, 0, 1));
                data = new InMemorySampleData (new DefaultAudioMetadata (1, sampleRate, bitsPerSample, frames), part);
                offset = keyStarts[key];
                panning = 0;
            }
            else
            {
                final int group = findGroup (groups, key);
                if (groupData[group] == null)
                {
                    final byte [] part = Arrays.copyOfRange (audio, groupStarts[group] * bytesPerFrame, groupEnds[group] * bytesPerFrame);
                    groupData[group] = new InMemorySampleData (new DefaultAudioMetadata (channels, sampleRate, bitsPerSample, groupEnds[group] - groupStarts[group]), part);
                }
                data = groupData[group];
                offset = groupStarts[group];
            }

            final ISampleZone zone = new DefaultSampleZone (name + " " + (key + 1), data);
            final int note = Op1FieldTag.LOWEST_NOTE + key;
            zone.setKeyLow (note);
            zone.setKeyHigh (note);
            // The key plays the part transposed by its pitch
            final double semitones = pitches[key] / (double) Op1FieldTag.PITCH_PER_SEMITONE;
            final int keyRoot = Math.clamp (note - Math.round (semitones), 0, 127);
            zone.setKeyRoot (keyRoot);
            zone.setTuning (semitones - (note - keyRoot));
            zone.setStart (keyStarts[key] - offset);
            zone.setStop (keyEnds[key] - offset);
            zone.setGain (Op1FieldTag.volumeToGain (volumes[key]));
            zone.setPanning (panning);
            zone.setReversed (directions[key] >= Op1FieldTag.CENTER_VALUE);
            applyPlayMode (zone, playModes[key]);
            zones.add (zone);
        }
        return zones;
    }


    /**
     * Read a patch of the sampler synthesizer engine. It plays the sample chromatically across the
     * whole keyboard. The play range and the loop are positions in a sample buffer of 6 seconds.
     *
     * @param name The name of the patch
     * @param root The parameters of the patch
     * @param sampleData The AIFF file
     * @return The zone
     * @throws IOException Could not read the audio
     */
    private static List<ISampleZone> readSampler (final String name, final JsonNode root, final AiffFileSampleData sampleData) throws IOException
    {
        final IAudioMetadata audioMetadata = sampleData.getAudioMetadata ();
        final int numFrames = audioMetadata.getNumberOfSamples ();
        if (numFrames <= 0)
            return Collections.emptyList ();
        final double framesPerValue = Op1FieldTag.SAMPLER_SECONDS * (double) audioMetadata.getSampleRate () / Op1FieldTag.MAX_VALUE;

        // Start, loop in, loop out, end, direction, fine tune, loop fade, gain
        final int [] knobs = getValues (root, Op1FieldTag.TAG_KNOBS, 8, 0);
        final int start = Math.clamp (Math.round (knobs[0] * framesPerValue), 0, numFrames);
        int end = Math.clamp (Math.round (knobs[3] * framesPerValue), start, numFrames);
        if (end <= start)
            end = numFrames;
        if (end <= start)
            return Collections.emptyList ();

        final ISampleZone zone = new DefaultSampleZone (name, sampleData);
        zone.setStart (start);
        zone.setStop (end);
        zone.setKeyLow (0);
        zone.setKeyHigh (127);
        zone.setReversed (knobs[4] >= Op1FieldTag.CENTER_VALUE);

        // The sample sounds at its base frequency when it is played without transposing it
        final JsonNode frequencyNode = root.get (Op1FieldTag.TAG_BASE_FREQUENCY);
        final double frequency = frequencyNode == null ? 0 : frequencyNode.asDouble (0);
        if (frequency > 0)
        {
            final double note = 69 + 12 * Math.log (frequency / 440.0) / Math.log (2);
            final int keyRoot = Math.clamp (Math.round (note), 0, 127);
            zone.setKeyRoot (keyRoot);
            zone.setTuning (keyRoot - note);
        }
        else
            zone.setKeyRoot (60);

        final int loopIn = Math.clamp (Math.round (knobs[1] * framesPerValue), start, end);
        final int loopOut = Math.clamp (Math.round (knobs[2] * framesPerValue), start, end);
        if (loopOut > loopIn)
        {
            // The sample plays to its end when the key is released
            final ISampleLoop loop = new DefaultSampleLoop ();
            loop.setType (LoopType.FORWARDS);
            loop.setStart (loopIn);
            loop.setEnd (loopOut);
            loop.setLoopUntilRelease (true);
            zone.addLoop (loop);
        }

        return Collections.singletonList (zone);
    }


    /**
     * Apply the play mode of a key to its zone.
     *
     * @param zone The zone of the key
     * @param playMode The play mode of the key
     */
    private static void applyPlayMode (final ISampleZone zone, final int playMode)
    {
        switch (Math.clamp (playMode / PLAYMODE_RANGE, 0, 3))
        {
            // Gate: plays while the key is pressed
            case 0:
                break;

            // Loop: repeats the whole part while the key is pressed
            case 3:
                final ISampleLoop loop = new DefaultSampleLoop ();
                loop.setType (LoopType.FORWARDS);
                loop.setStart (zone.getStart ());
                loop.setEnd (zone.getStop () - 1);
                zone.addLoop (loop);
                break;

            // One shot and group: play the whole part
            default:
                zone.setOneShot (true);
                break;
        }
    }


    /**
     * Convert the AIFF file into a WAV file.
     *
     * @param sampleData The AIFF file
     * @return The WAV file
     * @throws IOException Could not convert the file
     */
    private static WaveFile convertToWav (final AiffFileSampleData sampleData) throws IOException
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream ();
        sampleData.writeSample (out);
        final WaveFile waveFile = new WaveFile ();
        try
        {
            waveFile.read (new ByteArrayInputStream (out.toByteArray ()), true);
        }
        catch (final ParseException ex)
        {
            throw new IOException (ex);
        }
        return waveFile;
    }


    /**
     * Mix the two channels of 16-bit stereo audio into one.
     *
     * @param stereo The interleaved little-endian audio
     * @param blend The share of the second channel in the range of [0..1]
     * @return The mono audio
     */
    private static byte [] blendChannels (final byte [] stereo, final double blend)
    {
        final byte [] mono = new byte [stereo.length / 2];
        for (int in = 0, out = 0; out + 1 < mono.length; in += 4, out += 2)
        {
            final int left = (short) (stereo[in] & 0xFF | stereo[in + 1] << 8);
            final int right = (short) (stereo[in + 2] & 0xFF | stereo[in + 3] << 8);
            final int value = (int) Math.round (left * (1 - blend) + right * blend);
            mono[out] = (byte) value;
            mono[out + 1] = (byte) (value >> 8);
        }
        return mono;
    }


    /**
     * Test if two keys play nearly the same part of the sample: the parts overlap by at least half
     * of the longer one.
     *
     * @param keyStarts The starts of the parts of the keys
     * @param keyEnds The ends of the parts of the keys
     * @param key The index of the one key
     * @param other The index of the other key
     * @return True if the parts are nearly the same
     */
    private static boolean isSamePart (final int [] keyStarts, final int [] keyEnds, final int key, final int other)
    {
        final int length = keyEnds[key] - keyStarts[key];
        final int otherLength = keyEnds[other] - keyStarts[other];
        if (length <= 0 || otherLength <= 0)
            return false;
        final int overlap = Math.min (keyEnds[key], keyEnds[other]) - Math.max (keyStarts[key], keyStarts[other]);
        return 2L * overlap >= Math.max (length, otherLength);
    }


    private static int findGroup (final int [] groups, final int key)
    {
        int group = key;
        while (groups[group] != group)
            group = groups[group];
        return group;
    }


    private static boolean isSilent (final byte [] audio, final int from, final int to)
    {
        for (int i = from; i < to; i++)
            if (audio[i] != 0)
                return false;
        return true;
    }


    private static int [] getValues (final JsonNode node, final String name, final int size, final int defaultValue)
    {
        final int [] values = new int [size];
        Arrays.fill (values, defaultValue);
        final JsonNode valuesNode = node.get (name);
        if (valuesNode != null && valuesNode.isArray ())
            for (int i = 0; i < Math.min (size, valuesNode.size ()); i++)
                values[i] = valuesNode.get (i).asInt (defaultValue);
        return values;
    }


    private static boolean [] getFlags (final JsonNode node, final String name, final int size)
    {
        final boolean [] flags = new boolean [size];
        final JsonNode flagsNode = node.get (name);
        if (flagsNode != null && flagsNode.isArray ())
            for (int i = 0; i < Math.min (size, flagsNode.size ()); i++)
                flags[i] = flagsNode.get (i).asBoolean (false);
        return flags;
    }


    private static String getText (final JsonNode node, final String name)
    {
        final JsonNode valueNode = node.get (name);
        return valueNode == null ? "" : valueNode.asText ("");
    }
}
