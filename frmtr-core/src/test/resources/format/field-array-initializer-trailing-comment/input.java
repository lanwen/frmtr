package codec;

class UnsharableCodecs {
    private static final byte[] lengthHeader = { 0x00, 0x00, 0x40, 0x00 }; // 4096 bytes

    private static final Logger log = LoggerFactory.getLogger(UnsharableCodecs.class);
}
