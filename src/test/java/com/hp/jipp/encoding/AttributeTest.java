package com.hp.jipp.encoding;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import static org.junit.Assert.*;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.hp.jipp.model.Status;
import com.hp.jipp.util.BuildError;
import com.hp.jipp.util.ParseError;
import com.hp.jipp.util.Util;
import com.hp.jipp.model.Attributes;
import com.hp.jipp.model.Operation;

import static com.hp.jipp.encoding.Cycler.*;

public class AttributeTest {

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @Test
    public void octetString() throws IOException {
        AttributeType<byte[]> octetStringType = new OctetStringType(Tag.OctetString, "name");
        Attribute<byte[]> attribute = octetStringType.of("value".getBytes(Util.UTF8));
        assertArrayEquals(new byte[] {
                (byte)0x30, // OctetString
                (byte)0x00,
                (byte)0x04,
                'n', 'a', 'm', 'e',
                (byte)0x00,
                (byte)0x05,
                'v', 'a', 'l', 'u', 'e'
        }, toBytes(attribute));
        attribute = cycle(attribute);
        assertEquals(Tag.OctetString, attribute.getValueTag());
        assertEquals("name", attribute.getName());
        assertArrayEquals("value".getBytes(Util.UTF8), attribute.getValue(0));
    }

    @Test
    public void multiOctetString() throws IOException {
        AttributeType<byte[]> stringType = new OctetStringType(Tag.NameWithoutLanguage, "name");
        Attribute<byte[]> attribute = stringType.of("value".getBytes(Util.UTF8), "value2".getBytes(Util.UTF8));
        assertArrayEquals("value".getBytes(Util.UTF8), attribute.getValue(0));
        assertArrayEquals("value2".getBytes(Util.UTF8), attribute.getValue(1));
    }

    @Test
    public void multiBoolean() throws IOException {
        AttributeType<Boolean> booleanType = new BooleanType(Tag.BooleanValue, "name");
        Attribute<Boolean> attribute = cycle(booleanType.of(true, false));
        assertEquals(ImmutableList.of(true, false), attribute.getValues());
    }

    @Test
    public void multiInteger() throws IOException {
        AttributeType<Integer> integerType = new IntegerType(Tag.IntegerValue, "name");
        Attribute<Integer> attribute = cycle(integerType.of(-50505, 50505));
        assertEquals(ImmutableList.of(-50505, 50505), attribute.getValues());
    }

    @Test
    public void enumAttribute() throws IOException {
        AttributeGroup group = Cycler.cycle(AttributeGroup.Companion.of(Tag.PrinterAttributes,
                Attributes.OperationsSupported.of(
                        Operation.CancelJob, Operation.GetJobAttributes, Operation.CreateJob)));
        assertEquals(ImmutableList.of(Operation.CancelJob, Operation.GetJobAttributes, Operation.CreateJob),
                group.getValues(Attributes.OperationsSupported));
    }

    @Test
    public void surpriseEnum() throws IOException {
        AttributeGroup group = Cycler.cycle(AttributeGroup.Companion.of(Tag.PrinterAttributes,
                Attributes.OperationsSupported.of(
                        Operation.of("vendor-specific", 0x4040))));
        // We can't know it's called "vendor-specific" after parsing, since we just made it up.
        // So expect the unrecognized format
        assertEquals(ImmutableList.of(Operation.of("Operation(x4040)", 0x4040)),
                group.getValues(Attributes.OperationsSupported));
    }

    @Test
    public void badCollection() throws IOException {
        exception.expect(ParseError.class);
        exception.expectMessage("Bad tag in collection: printer-attributes");

        byte[] bytes = new byte[] {
//                (byte)Tag.BeginCollection.getCode(), // Read already
                (byte)0x00,
                (byte)0x09,
                'm', 'e', 'd' , 'i', 'a', '-', 'c', 'o', 'l',
                (byte)0x00,
                (byte)0x00,
                (byte) Tag.PrinterAttributes.getCode(), // NOT a good delimiter
                (byte)0x00,
                (byte)0x00,
                (byte)0x00,
                (byte)0x00
        };
        Attribute.Companion.read(new DataInputStream(new ByteArrayInputStream(bytes)), sFinder, Tag.BeginCollection);
    }

    @Test
    public void collection() throws IOException {
        // Let's encode:
        // media-col = {
        //   media-color: blue,
        //   media-size: [ {
        //       x-dimension = 6,
        //       y-dimension = 4
        //     }, {
        //       x-dimension = 12,
        //       y-dimension = 5
        //     }
        //  }

        CollectionType mediaColType = new CollectionType("media-col");
        CollectionType mediaSizeType = new CollectionType("media-size");
        StringType colorType = new StringType(Tag.Keyword, "media-color");
        IntegerType xDimensionType = new IntegerType(Tag.IntegerValue, "x-dimension");
        IntegerType yDimensionType = new IntegerType(Tag.IntegerValue, "y-dimension");

        Attribute<AttributeCollection> mediaCol = mediaColType.of(
                new AttributeCollection(
                        colorType.of("blue"),
                        mediaSizeType.of(
                                new AttributeCollection(
                                        xDimensionType.of(6),
                                        yDimensionType.of(4)),
                                new AttributeCollection(
                                        xDimensionType.of(12),
                                        yDimensionType.of(5))

                )));

        mediaCol = cycle(mediaCol);

        // Spot-check elements of the collection
        assertEquals("media-col", mediaCol.getName());
        assertEquals("blue", mediaCol.getValues().get(0).values(colorType).get(0));
        assertEquals(Integer.valueOf(12),
                mediaCol.getValues().get(0)
                        .values(mediaSizeType).get(1)
                        .values(xDimensionType).get(0));

        // Make sure we're covering some empty cases
        assertFalse(mediaCol.getValues().get(0).get(xDimensionType).isPresent());
        assertEquals(0, mediaCol.getValues().get(0).values(xDimensionType).size());

        // Output is helpful for inspection
        System.out.println(mediaCol);
    }

    @Test
    public void invalidTag() {
        exception.expect(BuildError.class);
        // String is not Integer; should throw.
        new StringType(Tag.IntegerValue, "something");
    }

    @Test
    public void missingEncoder() throws Exception {
        exception.expect(ParseError.class);
        byte[] bytes = new byte[] {
                0, 2, 'x', 0,
                1,
                0
        };
        Attribute.Companion.read(new DataInputStream(new ByteArrayInputStream(bytes)),
                new Attribute.EncoderFinder() {
                    @Override
                    public Attribute.BaseEncoder<?> find(Tag valueTag, String name) throws IOException {
                        throw new ParseError("");
                    }
                }, Tag.OctetString);
    }

    @Test
    public void insufficientLength() throws Exception {
        exception.expect(ParseError.class);
        exception.expectMessage("Bad attribute length: expected 4, got 1");
        byte[] bytes = new byte[] {
                0,
                0,
                0,
                1,
                0
        };
        Attribute.Companion.read(new DataInputStream(new ByteArrayInputStream(bytes)), Cycler.sFinder, Tag.IntegerValue);
    }

    @Test
    public void badTag() throws Exception {
        exception.expect(BuildError.class);
        exception.expectMessage("Invalid tag(x77) for Integer");
        new Attribute<>(Tag.get(0x77), "", ImmutableList.of(5), IntegerType.ENCODER);
    }

    @Test
    public void tagNames() throws Exception {
        assertEquals("Integer", IntegerType.ENCODER.getType());
        assertEquals("OctetString", OctetStringType.ENCODER.getType());
        assertEquals("RangeOfInteger", RangeOfIntegerType.ENCODER.getType());
        assertEquals("Resolution", ResolutionType.ENCODER.getType());
        assertEquals("LangString", LangStringType.ENCODER.getType());
        assertEquals("Integer", IntegerType.ENCODER.getType());
        assertEquals("URI", UriType.ENCODER.getType());
        assertEquals("Collection", CollectionType.ENCODER.getType());
        assertEquals("Boolean", BooleanType.ENCODER.getType());
        assertEquals("String", StringType.ENCODER.getType());
        assertEquals("Status", Status.ENCODER.getType());
    }

    @Test
    public void resolutionUnits() throws Exception {
        byte[] bytes = new byte[] {
                0,
                9,
                0,
                0,
                1,
                0,
                0,
                0,
                2,
                0,
                5,
        };

        Resolution resolution = ResolutionType.ENCODER.readValue(
                new DataInputStream(new ByteArrayInputStream(bytes)), Tag.Resolution);
        assertEquals("256x512 ResolutionUnit(x5)", resolution.toString());
    }

    @Test
    public void shortRead() throws IOException {
        exception.expect(ParseError.class);
        exception.expectMessage("Value too short: expected 2 but got 1");
        byte[] bytes = new byte[] {
                0,
                2,
                0,
        };
        Attribute.readValueBytes2(new DataInputStream(new ByteArrayInputStream(bytes)));
    }

    @Test
    public void badConversion() throws IOException {
        assertEquals(Optional.absent(), Attributes.JobId.of(Attributes.JobName.of("string")));
    }

    @Test
    public void goodConversion() throws IOException {
        assertEquals(Optional.of(Attributes.JobId.of(1)),
                Attributes.JobId.of(new IntegerType(Tag.IntegerValue, "job-id").of(1)));
    }

    @Test
    public void printBinary() throws Exception {
        assertTrue(new OctetStringType(Tag.OctetString, "data").of(new byte[] { 1, 2, 3 }).toString().contains("x010203"));
    }

    @Test
    public void equals() throws IOException {
        Attribute<String> jobName = Attributes.JobName.of("hello");
        assertEquals(jobName.hashCode(), cycle(jobName).hashCode());
    }
}
