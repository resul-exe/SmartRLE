import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;

public class BenchmarkRunner {
    private static class Result {
        long originalBytes;
        long smartRleBytes;
        long gzipBytes;
        double compressMsSmart;
        double decompressMsSmart;
        double compressMsGzip;
        boolean correctness;
    }

    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0] : "apache_access_5mb.log";
        byte[] data = Files.readAllBytes(Paths.get(file));
        String text = new String(data, StandardCharsets.UTF_8);

        // Warm-up
        SmartRLE warm = new SmartRLE();
        warm.compress(text);

        Result r = runOnce(text);

        System.out.println("=== Benchmark on: " + file + " ===");
        System.out.println("Original size (bytes): " + r.originalBytes);
        System.out.println("SmartRLE size (bytes): " + r.smartRleBytes + " (ratio: " + percent(r.smartRleBytes, r.originalBytes) + "%)");
        System.out.println("GZIP size (bytes): " + r.gzipBytes + " (ratio: " + percent(r.gzipBytes, r.originalBytes) + "%)");
        System.out.println("SmartRLE compress ms: " + fmt(r.compressMsSmart));
        System.out.println("SmartRLE decompress ms: " + fmt(r.decompressMsSmart));
        System.out.println("GZIP compress ms: " + fmt(r.compressMsGzip));
        System.out.println("Correctness (SmartRLE): " + r.correctness);
    }

    private static Result runOnce(String text) throws Exception {
        Result r = new Result();
        r.originalBytes = text.getBytes(StandardCharsets.UTF_8).length;

        SmartRLE codec = new SmartRLE();
        long t0 = System.nanoTime();
        String compressed = codec.compress(text);
        long t1 = System.nanoTime();
        String decompressed = codec.decompress(compressed);
        long t2 = System.nanoTime();
        r.compressMsSmart = (t1 - t0) / 1_000_000.0;
        r.decompressMsSmart = (t2 - t1) / 1_000_000.0;
        r.smartRleBytes = compressed.getBytes(StandardCharsets.UTF_8).length;
        r.correctness = text.equals(decompressed);

        long t3 = System.nanoTime();
        byte[] gz = gzip(text.getBytes(StandardCharsets.UTF_8));
        long t4 = System.nanoTime();
        r.compressMsGzip = (t4 - t3) / 1_000_000.0;
        r.gzipBytes = gz.length;

        return r;
    }

    private static String percent(long num, long den) {
        if (den == 0) return "0.00";
        double v = (num * 100.0) / den;
        return String.format("%.2f", v);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(input);
        }
        return baos.toByteArray();
    }
}


