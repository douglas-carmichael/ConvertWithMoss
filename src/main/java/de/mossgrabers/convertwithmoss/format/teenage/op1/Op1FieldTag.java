// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2019-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.convertwithmoss.format.teenage.op1;

/**
 * Constants of the Teenage Engineering OP-1 and OP-1 Field patch format. A patch is an AIFF file
 * whose application chunk with the signature 'op-1' contains the parameters of the patch as a JSON
 * document. A drum kit plays one part of the sample on each of the 24 keys, a patch of the sampler
 * synthesizer engine plays the whole sample chromatically.
 *
 * @author Jürgen Moßgraber
 */
public class Op1FieldTag
{
    /** The signature of the application chunk which contains the patch parameters. */
    public static final String APPLICATION_SIGNATURE = "op-1";
    /** The size of the JSON document of a drum kit including the line feed and the padding. */
    public static final int    DRUM_JSON_SIZE        = 4096;
    /** The AIFC version which the device writes. */
    public static final long   AIFC_VERSION          = 0xA2805140L;

    /** The type of the patch. */
    public static final String TAG_TYPE              = "type";
    /** The name of the patch. */
    public static final String TAG_NAME              = "name";
    /** The version of the drum kit format. */
    public static final String TAG_DRUM_VERSION      = "drum_version";
    /** The time of the last modification of the patch in seconds since 1970. */
    public static final String TAG_MODIFICATION_TIME = "mtime";
    /** The octave of the keyboard. */
    public static final String TAG_OCTAVE            = "octave";
    /** Is the sample stereo? */
    public static final String TAG_STEREO            = "stereo";
    /** The start of the part of the sample which each key plays. */
    public static final String TAG_START             = "start";
    /** The end of the part of the sample which each key plays. */
    public static final String TAG_END               = "end";
    /** The tuning of each key. */
    public static final String TAG_PITCH             = "pitch";
    /** The volume of each key. */
    public static final String TAG_VOLUME            = "volume";
    /** The panning of each key. */
    public static final String TAG_PAN               = "pan";
    /** Does the panning of a key blend between the two channels (A/B) instead of balancing them? */
    public static final String TAG_PAN_AB            = "pan_ab";
    /** The play mode of each key. */
    public static final String TAG_PLAYMODE          = "playmode";
    /** The play direction of each key. */
    public static final String TAG_REVERSE           = "reverse";
    /** The fade of each key. */
    public static final String TAG_ATTACK            = "attack";
    /** The envelope of a drum kit. */
    public static final String TAG_DYNAMIC_ENVELOPE  = "dyna_env";
    /** Is the effect active? */
    public static final String TAG_FX_ACTIVE         = "fx_active";
    /** The type of the effect. */
    public static final String TAG_FX_TYPE           = "fx_type";
    /** The parameters of the effect. */
    public static final String TAG_FX_PARAMS         = "fx_params";
    /** Is the LFO active? */
    public static final String TAG_LFO_ACTIVE        = "lfo_active";
    /** The type of the LFO. */
    public static final String TAG_LFO_TYPE          = "lfo_type";
    /** The parameters of the LFO. */
    public static final String TAG_LFO_PARAMS        = "lfo_params";
    /** The parameters of a synthesizer engine. */
    public static final String TAG_KNOBS             = "knobs";
    /** The frequency at which the sampler engine plays its sample without transposing it. */
    public static final String TAG_BASE_FREQUENCY    = "base_freq";

    /** The type of a drum kit which plays a sample. */
    public static final String TYPE_DRUM             = "drum";
    /** The type of a patch of the sampler synthesizer engine. */
    public static final String TYPE_SAMPLER          = "sampler";

    /** The version of the drum kits of the OP-1 Field. */
    public static final int    DRUM_VERSION          = 2;

    /** The number of keys of the keyboard. Each plays one part of the sample of a drum kit. */
    public static final int    NUMBER_OF_KEYS        = 24;
    /** The MIDI note of the lowest key. */
    public static final int    LOWEST_NOTE           = 53;

    /** The sample rate of the device. */
    public static final int    SAMPLE_RATE           = 44100;
    /** The longest drum kit with a mono sample in seconds. */
    public static final int    MAX_SECONDS_MONO      = 12;
    /** The longest drum kit with a stereo sample in seconds. */
    public static final int    MAX_SECONDS_STEREO    = 20;
    /** The value of a position in a drum kit which points at the end of its longest sample. */
    public static final double MAX_POSITION          = 2147483646.0;
    /** The positions of the sampler synthesizer engine relate to a sample of this length. */
    public static final int    SAMPLER_SECONDS       = 6;

    /** The maximum value of a parameter. */
    public static final int    MAX_VALUE             = 32767;
    /** The value of a parameter at its center, e.g. the panning. */
    public static final int    CENTER_VALUE          = 16384;
    /**
     * The volume which the drum kits made on the device store for keys whose gain was not changed.
     * It is read and written as a gain of 0dB, the volume is a linear factor of it.
     */
    public static final int    DEFAULT_VOLUME        = 15383;
    /** The pitch value of one semi-tone. */
    public static final int    PITCH_PER_SEMITONE    = 512;
    /** The highest pitch value: 4 octaves up, the lowest is the negative value. */
    public static final int    MAX_PITCH             = 24576;

    /** The play mode which plays the sound while the key is pressed. */
    public static final int    PLAYMODE_GATE         = 4096;
    /** The play mode which plays the whole sound. */
    public static final int    PLAYMODE_ONE_SHOT     = 12288;
    /** The play mode 'group'. */
    public static final int    PLAYMODE_GROUP        = 20480;
    /** The play mode which repeats the sound while the key is pressed. */
    public static final int    PLAYMODE_LOOP         = 28672;
    /** The play direction forwards. */
    public static final int    DIRECTION_FORWARD     = 8192;
    /** The play direction backwards. */
    public static final int    DIRECTION_REVERSE     = 24576;


    /**
     * Private constructor for utility class.
     */
    private Op1FieldTag ()
    {
        // Intentionally empty
    }


    /**
     * Get the factor which converts a sample frame into a position of a drum kit. The positions
     * relate to the longest kit the device holds, which is shorter for a mono sample.
     *
     * @param numberOfChannels The number of channels of the sample
     * @param sampleRate The sample rate of the sample
     * @return The factor
     */
    public static double getPositionsPerFrame (final int numberOfChannels, final int sampleRate)
    {
        return MAX_POSITION / ((double) sampleRate * getMaxSeconds (numberOfChannels));
    }


    /**
     * Get the length of the longest drum kit.
     *
     * @param numberOfChannels The number of channels of the sample
     * @return The length in seconds
     */
    public static int getMaxSeconds (final int numberOfChannels)
    {
        return numberOfChannels == 2 ? MAX_SECONDS_STEREO : MAX_SECONDS_MONO;
    }


    /**
     * Convert the volume of a key into a gain.
     *
     * @param volume The volume of a key
     * @return The gain in dB
     */
    public static double volumeToGain (final int volume)
    {
        return 20.0 * Math.log10 (volume / (double) DEFAULT_VOLUME);
    }


    /**
     * Convert a gain into the volume of a key.
     *
     * @param gain The gain in dB
     * @return The volume of a key
     */
    public static int gainToVolume (final double gain)
    {
        return Math.clamp (Math.round (DEFAULT_VOLUME * Math.pow (10, gain / 20.0)), 0, MAX_VALUE);
    }


    /**
     * Convert the panning of a key into the panning of the model.
     *
     * @param pan The panning of a key, 0 is full left
     * @return The panning in the range of [-1..1]
     */
    public static double panToPanning (final int pan)
    {
        return Math.clamp ((pan - CENTER_VALUE) / (double) CENTER_VALUE, -1, 1);
    }


    /**
     * Convert the panning of the model into the panning of a key.
     *
     * @param panning The panning in the range of [-1..1]
     * @return The panning of a key
     */
    public static int panningToPan (final double panning)
    {
        return Math.clamp (Math.round (CENTER_VALUE + panning * CENTER_VALUE), 0, MAX_VALUE);
    }


    /**
     * Convert a transposition into the pitch of a key.
     *
     * @param semitones The transposition in semi-tones
     * @return The pitch of a key
     */
    public static int semitonesToPitch (final double semitones)
    {
        return Math.clamp (Math.round (semitones * PITCH_PER_SEMITONE), -MAX_PITCH, MAX_PITCH);
    }
}
