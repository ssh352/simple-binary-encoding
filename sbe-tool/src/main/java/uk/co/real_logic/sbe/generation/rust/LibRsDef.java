/*
 * Copyright 2013-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.sbe.generation.rust;

import uk.co.real_logic.sbe.ir.Ir;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.stream.Stream;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static uk.co.real_logic.sbe.generation.rust.RustGenerator.BUF_LIFETIME;
import static uk.co.real_logic.sbe.generation.rust.RustGenerator.READ_BUF_TYPE;
import static uk.co.real_logic.sbe.generation.rust.RustGenerator.WRITE_BUF_TYPE;
import static uk.co.real_logic.sbe.generation.rust.RustUtil.TYPE_NAME_BY_PRIMITIVE_TYPE_MAP;
import static uk.co.real_logic.sbe.generation.rust.RustUtil.indent;
import static uk.co.real_logic.sbe.generation.rust.RustUtil.rustTypeName;
import static uk.co.real_logic.sbe.generation.rust.RustUtil.toLowerSnakeCase;

/**
 * Generates `lib.rs` specific code.
 */
class LibRsDef
{
    private final RustOutputManager outputManager;
    private final ByteOrder byteOrder;
    private final String schemaVersionType;

    /**
     * Create a new 'lib.rs' for the library being generated.
     *
     * @param outputManager     for generating the codecs to.
     * @param byteOrder         for the Encoding.
     * @param schemaVersionType for acting_version type.
     */
    LibRsDef(
        final RustOutputManager outputManager,
        final ByteOrder byteOrder,
        final String schemaVersionType)
    {
        this.outputManager = outputManager;
        this.byteOrder = byteOrder;
        this.schemaVersionType = schemaVersionType;
    }

    void generate(final Ir ir) throws IOException
    {
        try (Writer libRs = outputManager.createOutput("lib"))
        {
            indent(libRs, 0, "#![forbid(unsafe_code)]\n");
            indent(libRs, 0, "#![allow(clippy::all)]\n");
            indent(libRs, 0, "#![allow(non_camel_case_types)]\n\n");
            indent(libRs, 0, "#![allow(dead_code)]\n");
            indent(libRs, 0, "#![allow(private_bounds)]\n");
            indent(libRs, 0, "#![allow(ambiguous_glob_reexports)]\n\n");
            indent(libRs, 0, "use ::core::{convert::TryInto};\n\n");
            indent(libRs, 0, "use agrona::buffer::{\n");
            indent(libRs, 1, "BufferError, DirectBuffer, MutableDirectBuffer, UnsafeBuffer, UnsafeBufferMut,\n");
            indent(libRs, 1, "UnsafeBufferView,\n");
            indent(libRs, 0, "};\n\n");

            final ArrayList<String> modules = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(outputManager.getSrcDirPath()))
            {
                walk
                    .filter(Files::isRegularFile)
                    .map((path) -> path.getFileName().toString())
                    .filter((fileName) -> fileName.endsWith(".rs"))
                    .filter((fileName) -> !fileName.equals("lib.rs"))
                    .map((fileName) -> fileName.substring(0, fileName.length() - 3))
                    .sorted()
                    .forEach(modules::add);
            }

            // add modules
            for (final String mod : modules)
            {
                indent(libRs, 0, "pub mod %s;\n", toLowerSnakeCase(mod));
            }
            indent(libRs, 0, "\n");

            generateSbeSchemaConsts(libRs, ir);

            generateSbeErrorEnum(libRs);
            generateEitherEnum(libRs);

            generateEncoderTraits(libRs);
            generateDecoderTraits(schemaVersionType, libRs);

            generateBufferHelpers(libRs, byteOrder);
            generateReadBuf(libRs, byteOrder);
            generateWriteBuf(libRs, byteOrder);
        }
    }

    static void generateEncoderTraits(final Writer writer) throws IOException
    {
        indent(writer, 0, "pub(crate) trait Writer: Sized {\n");
        indent(writer, 1, "type Buffer: MutableDirectBuffer;\n\n");
        indent(writer, 1, "fn get_buf_mut(&self) -> &Self::Buffer;\n");
        indent(writer, 0, "}\n\n");

        indent(writer, 0, "pub trait Encoder: Writer {\n");
        indent(writer, 1, "fn get_limit(&self) -> usize;\n");
        indent(writer, 1, "fn set_limit(&mut self, limit: usize);\n");
        indent(writer, 1, "fn nullify_optional_fields(&mut self) -> &mut Self {\n");
        indent(writer, 2, "self\n");
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n\n");

        indent(writer, 0, "impl<B: MutableDirectBuffer> Writer for B {\n");
        indent(writer, 1, "type Buffer = B;\n\n");
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn get_buf_mut(&self) -> &Self::Buffer {\n");
        indent(writer, 2, "self\n");
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n\n");
    }

    static void generateDecoderTraits(final String schemaVersionType, final Writer writer) throws IOException
    {
        indent(writer, 0, "pub trait ActingVersion {\n");
        indent(writer, 1, "fn acting_version(&self) -> %s;\n", schemaVersionType);
        indent(writer, 0, "}\n\n");

        indent(writer, 0, "pub(crate) trait Reader: Sized {\n");
        indent(writer, 1, "type Buffer: DirectBuffer;\n\n");
        indent(writer, 1, "fn get_buf(&self) -> &Self::Buffer;\n");
        indent(writer, 0, "}\n\n");

        indent(writer, 0, "pub trait Decoder: Reader {\n");
        indent(writer, 1, "fn get_limit(&self) -> usize;\n");
        indent(writer, 1, "fn set_limit(&mut self, limit: usize);\n");
        indent(writer, 0, "}\n\n");

        indent(writer, 0, "impl<B: DirectBuffer> Reader for B {\n");
        indent(writer, 1, "type Buffer = B;\n\n");
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn get_buf(&self) -> &Self::Buffer {\n");
        indent(writer, 2, "self\n");
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n\n");
    }

    static void generateSbeSchemaConsts(final Writer writer, final Ir ir) throws IOException
    {
        final String schemaIdType = rustTypeName(ir.headerStructure().schemaIdType());
        final String schemaVersionType = rustTypeName(ir.headerStructure().schemaVersionType());
        final String semanticVersion = ir.semanticVersion() == null ? "" : ir.semanticVersion();

        indent(writer, 0, "pub const SBE_SCHEMA_ID: %s = %d;\n", schemaIdType, ir.id());
        indent(writer, 0, "pub const SBE_SCHEMA_VERSION: %s = %d;\n", schemaVersionType, ir.version());
        indent(writer, 0, "pub const SBE_SEMANTIC_VERSION: &str = \"%s\";\n\n", semanticVersion);
    }

    static void generateSbeErrorEnum(final Writer writer) throws IOException
    {
        indent(writer, 0, "pub type SbeResult<T> = core::result::Result<T, SbeErr>;\n\n");
        indent(writer, 0, "#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]\n");
        indent(writer, 0, "pub enum SbeErr {\n");
        indent(writer, 1, "ParentNotSet,\n");
        indent(writer, 0, "}\n");

        indent(writer, 0, "impl core::fmt::Display for SbeErr {\n");
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {\n");
        indent(writer, 2, "write!(f, \"{self:?}\")\n");
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n");

        indent(writer, 0, "impl std::error::Error for SbeErr {}\n\n");
    }

    static void generateEitherEnum(final Writer writer) throws IOException
    {
        indent(writer, 0, "#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]\n");
        indent(writer, 0, "pub enum Either<L, R> {\n");
        indent(writer, 1, "Left(L),\n");
        indent(writer, 1, "Right(R),\n");
        indent(writer, 0, "}\n\n");
    }

    static void generateBufferHelpers(final Appendable writer, final ByteOrder byteOrder) throws IOException
    {
        final LinkedHashSet<String> uniquePrimitiveTypes
            = new LinkedHashSet<>(TYPE_NAME_BY_PRIMITIVE_TYPE_MAP.values());
        final String endianness = byteOrder == LITTLE_ENDIAN ? "le" : "be";

        indent(writer, 0, "#[inline]\n");
        indent(writer, 0, "pub(crate) fn get_bytes_at<const N: usize, B: DirectBuffer + ?Sized>(\n");
        indent(writer, 1, "buf: &B,\n");
        indent(writer, 1, "index: usize,\n");
        indent(writer, 0, ") -> [u8; N] {\n");
        indent(writer, 1, "buf.get_bytes(index, N).try_into().expect(\"slice with incorrect length\")\n");
        indent(writer, 0, "}\n\n");

        indent(writer, 0, "#[inline]\n");
        indent(writer, 0, "pub(crate) fn get_slice_at<B: DirectBuffer + ?Sized>(\n");
        indent(writer, 1, "buf: &B,\n");
        indent(writer, 1, "index: usize,\n");
        indent(writer, 1, "len: usize,\n");
        indent(writer, 0, ") -> &[u8] {\n");
        indent(writer, 1, "buf.get_bytes(index, len)\n");
        indent(writer, 0, "}\n\n");

        indent(writer, 0, "#[inline]\n");
        indent(writer, 0, "pub(crate) fn put_slice_at<B: MutableDirectBuffer + ?Sized>(\n");
        indent(writer, 1, "buf: &B,\n");
        indent(writer, 1, "index: usize,\n");
        indent(writer, 1, "src: &[u8],\n");
        indent(writer, 0, ") -> usize {\n");
        indent(writer, 1, "buf.put_bytes(index, src);\n");
        indent(writer, 1, "src.len()\n");
        indent(writer, 0, "}\n\n");

        for (final String primitiveType : uniquePrimitiveTypes)
        {
            indent(writer, 0, "#[inline]\n");
            indent(writer, 0, "pub(crate) fn get_%1$s_at<B: DirectBuffer + ?Sized>(buf: &B, index: usize) -> %1$s {\n",
                primitiveType);

            if ("u8".equals(primitiveType))
            {
                indent(writer, 1, "buf.get_bytes(index, 1)[0]\n");
            }
            else if ("i8".equals(primitiveType))
            {
                indent(writer, 1, "buf.get_bytes(index, 1)[0] as i8\n");
            }
            else
            {
                indent(writer, 1, "%s::from_%s_bytes(get_bytes_at(buf, index))\n", primitiveType, endianness);
            }

            indent(writer, 0, "}\n\n");

            indent(writer, 0, "#[inline]\n");
            indent(writer, 0,
                "pub(crate) fn put_%1$s_at<B: MutableDirectBuffer + ?Sized>(buf: &B, index: usize, value: %1$s) {\n",
                primitiveType);

            if ("u8".equals(primitiveType) || "i8".equals(primitiveType))
            {
                indent(writer, 1, "buf.put_byte(index, value as u8);\n");
            }
            else
            {
                indent(writer, 1, "buf.put_bytes(index, &%s::to_%s_bytes(value));\n", primitiveType, endianness);
            }

            indent(writer, 0, "}\n\n");
        }
    }

    static void generateReadBuf(final Appendable writer, final ByteOrder byteOrder) throws IOException
    {
        indent(writer, 0, "#[derive(Clone, Copy)]\n");
        indent(writer, 0, "pub struct %s<%s> {\n", READ_BUF_TYPE, BUF_LIFETIME);
        RustUtil.indent(writer, 1, "data: UnsafeBufferView<%s>,\n", BUF_LIFETIME);
        indent(writer, 0, "}\n");

        indent(writer, 0, "impl<%s> core::fmt::Debug for %s<%1$s> {\n", BUF_LIFETIME, READ_BUF_TYPE);
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {\n");
        indent(writer, 2, "f.debug_struct(\"%s\").field(\"capacity\", &self.capacity()).finish()\n", READ_BUF_TYPE);
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n\n");

        indent(writer, 0, "impl<%s> Default for %s<%1$s> {\n", BUF_LIFETIME, READ_BUF_TYPE);
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn default() -> Self {\n");
        indent(writer, 2, "Self::new(&[])\n");
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n\n");

        // impl ReadBuf ...
        indent(writer, 0, "#[allow(dead_code)]\n");
        indent(writer, 0, "impl<%s> %s<%s> {\n", BUF_LIFETIME, READ_BUF_TYPE, BUF_LIFETIME);
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "pub fn new(data: &%s [u8]) -> Self {\n", BUF_LIFETIME);
        indent(writer, 2, "Self { data: UnsafeBuffer::from_slice(data) }\n");
        indent(writer, 1, "}\n\n");
        writer.append("}\n");

        indent(writer, 0, "\n");
        indent(writer, 0, "impl<%s> DirectBuffer for %s<%1$s> {\n", BUF_LIFETIME, READ_BUF_TYPE);
        generateDirectBufferDelegates(writer, "UnsafeBufferView");
        indent(writer, 0, "}\n");
    }

    static void generateWriteBuf(final Writer writer, final ByteOrder byteOrder) throws IOException
    {
        indent(writer, 0, "\n");
        indent(writer, 0, "pub struct %s<%s> {\n", WRITE_BUF_TYPE, BUF_LIFETIME);
        indent(writer, 1, "data: UnsafeBufferMut<%s>,\n", BUF_LIFETIME);
        indent(writer, 0, "}\n");

        indent(writer, 0, "impl<%s> core::fmt::Debug for %s<%1$s> {\n", BUF_LIFETIME, WRITE_BUF_TYPE);
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {\n");
        indent(writer, 2, "f.debug_struct(\"%s\").field(\"capacity\", &self.capacity()).finish()\n", WRITE_BUF_TYPE);
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n\n");

        indent(writer, 0, "impl<%s> %s<%s> {\n", BUF_LIFETIME, WRITE_BUF_TYPE, BUF_LIFETIME);
        indent(writer, 1, "pub fn new(data: &%s mut [u8]) -> Self {\n", BUF_LIFETIME);
        indent(writer, 2, "Self { data: UnsafeBuffer::from_slice_mut(data) }\n");
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n");

        indent(writer, 0, "impl<%s> DirectBuffer for %s<%1$s> {\n", BUF_LIFETIME, WRITE_BUF_TYPE);
        generateDirectBufferDelegates(writer, "UnsafeBufferMut");
        indent(writer, 0, "}\n\n");

        indent(writer, 0, "impl<%s> MutableDirectBuffer for %s<%1$s> {\n", BUF_LIFETIME, WRITE_BUF_TYPE);
        generateMutableDirectBufferDelegates(writer, "UnsafeBufferMut");
        indent(writer, 0, "}\n\n");

        // impl From<WriteBuf> for &[u8]
        indent(writer, 0, "impl<%s> From<&%1$s mut %2$s<%1$s>> for &%1$s mut [u8] {\n", BUF_LIFETIME, WRITE_BUF_TYPE);
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn from(buf: &%s mut %2$s<%1$s>) -> &%1$s mut [u8] {\n", BUF_LIFETIME, WRITE_BUF_TYPE);
        indent(writer, 2, "buf.data.as_mut_slice()\n");
        indent(writer, 1, "}\n");
        indent(writer, 0, "}\n\n");
    }

    static void generateDirectBufferDelegates(final Appendable writer, final String innerType) throws IOException
    {
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn capacity(&self) -> usize {\n");
        indent(writer, 2, "<%s<'a> as DirectBuffer>::capacity(&self.data)\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn bounds_check(&self, offset: usize, length: usize) -> Result<(), BufferError> {\n");
        indent(writer, 2, "<%s<'a> as DirectBuffer>::bounds_check(&self.data, offset, length)\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn get_i32(&self, offset: usize) -> i32 {\n");
        indent(writer, 2, "<%s<'a> as DirectBuffer>::get_i32(&self.data, offset)\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn get_i64(&self, offset: usize) -> i64 {\n");
        indent(writer, 2, "<%s<'a> as DirectBuffer>::get_i64(&self.data, offset)\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn get_u16(&self, offset: usize) -> u16 {\n");
        indent(writer, 2, "<%s<'a> as DirectBuffer>::get_u16(&self.data, offset)\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn get_u32(&self, offset: usize) -> u32 {\n");
        indent(writer, 2, "<%s<'a> as DirectBuffer>::get_u32(&self.data, offset)\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn get_bytes(&self, offset: usize, length: usize) -> &[u8] {\n");
        indent(writer, 2, "<%s<'a> as DirectBuffer>::get_bytes(&self.data, offset, length)\n", innerType);
        indent(writer, 1, "}\n");
    }

    static void generateMutableDirectBufferDelegates(final Appendable writer, final String innerType) throws IOException
    {
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn put_i32(&self, offset: usize, value: i32) {\n");
        indent(writer, 2, "<%s<'a> as MutableDirectBuffer>::put_i32(&self.data, offset, value);\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn put_i64(&self, offset: usize, value: i64) {\n");
        indent(writer, 2, "<%s<'a> as MutableDirectBuffer>::put_i64(&self.data, offset, value);\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn put_u32(&self, offset: usize, value: u32) {\n");
        indent(writer, 2, "<%s<'a> as MutableDirectBuffer>::put_u32(&self.data, offset, value);\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn put_byte(&self, offset: usize, value: u8) {\n");
        indent(writer, 2, "<%s<'a> as MutableDirectBuffer>::put_byte(&self.data, offset, value);\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn put_bytes(&self, offset: usize, src: &[u8]) {\n");
        indent(writer, 2, "<%s<'a> as MutableDirectBuffer>::put_bytes(&self.data, offset, src);\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn set_memory(&self, offset: usize, length: usize, value: u8) {\n");
        indent(writer, 2, "<%s<'a> as MutableDirectBuffer>::set_memory(&self.data, offset, length, value);\n",
            innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn as_mut_ptr(&self) -> *mut u8 {\n");
        indent(writer, 2, "<%s<'a> as MutableDirectBuffer>::as_mut_ptr(&self.data)\n", innerType);
        indent(writer, 1, "}\n\n");

        indent(writer, 1, "#[allow(clippy::mut_from_ref)]\n");
        indent(writer, 1, "#[inline]\n");
        indent(writer, 1, "fn as_mut_slice(&self) -> &mut [u8] {\n");
        indent(writer, 2, "<%s<'a> as MutableDirectBuffer>::as_mut_slice(&self.data)\n", innerType);
        indent(writer, 1, "}\n");
    }
}
