// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2019-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.convertwithmoss.format.exs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import de.mossgrabers.convertwithmoss.file.StreamUtils;


/**
 * Structure for a EXS24 instrument.
 *
 * @author Jürgen Moßgraber
 */
class EXS24Instrument extends EXS24Object
{
    int numZoneBlocks;
    int numGroupBlocks;
    int numSampleBlocks;
    int numParameterBlocks;


    /**
     * Default constructor.
     */
    public EXS24Instrument ()
    {
        super (EXS24Block.TYPE_INSTRUMENT);
    }


    /**
     * Constructor.
     *
     * @param block The block to read
     * @throws IOException Could not read the block
     */
    public EXS24Instrument (final EXS24Block block) throws IOException
    {
        this ();
        this.read (block);
    }


    /** {@inheritDoc} */
    @Override
    protected void read (final InputStream in, final boolean isBigEndian) throws IOException
    {
        // No idea, always 0
        StreamUtils.readUnsigned32 (in, isBigEndian);

        this.numZoneBlocks = (int) StreamUtils.readUnsigned32 (in, isBigEndian);
        this.numGroupBlocks = (int) StreamUtils.readUnsigned32 (in, isBigEndian);
        this.numSampleBlocks = (int) StreamUtils.readUnsigned32 (in, isBigEndian);
        this.numParameterBlocks = (int) StreamUtils.readUnsigned32 (in, isBigEndian);

        // The number of blocks of the types 5 to 9: tagged records, kit piece information, key
        // names, unknown blocks (there are files with 16 of them, whose 4 bytes are all 0) and kit
        // piece records, which the conversion does not use
        StreamUtils.readUnsigned32 (in, isBigEndian);
        StreamUtils.readUnsigned32 (in, isBigEndian);
        StreamUtils.readUnsigned32 (in, isBigEndian);
        StreamUtils.readUnsigned32 (in, isBigEndian);
        StreamUtils.readUnsigned32 (in, isBigEndian);

        // There can be more bytes...
    }


    /** {@inheritDoc} */
    @Override
    protected void write (final OutputStream out, final boolean isBigEndian) throws IOException
    {
        // No idea, always 0
        StreamUtils.writeUnsigned32 (out, 0, isBigEndian);

        StreamUtils.writeUnsigned32 (out, this.numZoneBlocks, isBigEndian);
        StreamUtils.writeUnsigned32 (out, this.numGroupBlocks, isBigEndian);
        StreamUtils.writeUnsigned32 (out, this.numSampleBlocks, isBigEndian);
        StreamUtils.writeUnsigned32 (out, this.numParameterBlocks, isBigEndian);

        // The number of blocks of the types 5 to 9: tagged records, kit piece information, key
        // names, unknown blocks (there are files with 16 of them, whose 4 bytes are all 0) and kit
        // piece records; none of them is written
        StreamUtils.writeUnsigned32 (out, 0, isBigEndian);
        StreamUtils.writeUnsigned32 (out, 0, isBigEndian);
        StreamUtils.writeUnsigned32 (out, 0, isBigEndian);
        StreamUtils.writeUnsigned32 (out, 0, isBigEndian);
        StreamUtils.writeUnsigned32 (out, 0, isBigEndian);
    }
}
