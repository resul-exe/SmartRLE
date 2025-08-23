public class SmartRLETest {
    public static void main(String[] args) {
        SmartRLE compressor = new SmartRLE();

        // Test 1: Tekrarlayan desenler ile test
        String test1 = "aaaaaabbbbbbccccccdddddddeeeeeeffffffggggggghhhhhhiiiiiiijjjjjj";
        System.out.println("=== Test 1: Tekrarlayan Karakterler ===");
        System.out.println("Original: " + test1);
        System.out.println("Length: " + test1.length());

        String compressed1 = compressor.compress(test1);
        System.out.println("Compressed: " + compressed1);
        System.out.println("Length: " + compressed1.length());

        SmartRLE.CompressionStats stats1 = compressor.getStats(test1, compressed1);
        System.out.println("Stats: " + stats1);
        System.out.println();

        // Test 2: Metin sıkıştırma
        String test2 = "the quick brown fox jumps over the lazy dog. " +
                      "the quick brown fox jumps over the lazy dog. " +
                      "the quick brown fox jumps over the lazy dog.";
        System.out.println("=== Test 2: Tekrarlayan Metin ===");
        System.out.println("Original: " + test2);
        System.out.println("Length: " + test2.length());

        String compressed2 = compressor.compress(test2);
        System.out.println("Compressed: " + compressed2);
        System.out.println("Length: " + compressed2.length());

        SmartRLE.CompressionStats stats2 = compressor.getStats(test2, compressed2);
        System.out.println("Stats: " + stats2);
        System.out.println();

        // Test 3: Karmaşık desen
        String test3 = "ababababababababababababababababababababababababababababababab";
        System.out.println("=== Test 3: ABAB Deseni ===");
        System.out.println("Original: " + test3);
        System.out.println("Length: " + test3.length());

        String compressed3 = compressor.compress(test3);
        System.out.println("Compressed: " + compressed3);
        System.out.println("Length: " + compressed3.length());

        SmartRLE.CompressionStats stats3 = compressor.getStats(test3, compressed3);
        System.out.println("Stats: " + stats3);
        System.out.println();

        // Test 4: Rastgele metin
        String test4 = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
                      "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.";
        System.out.println("=== Test 4: Rastgele Metin ===");
        System.out.println("Original: " + test4);
        System.out.println("Length: " + test4.length());

        String compressed4 = compressor.compress(test4);
        System.out.println("Compressed: " + compressed4);
        System.out.println("Length: " + compressed4.length());

        SmartRLE.CompressionStats stats4 = compressor.getStats(test4, compressed4);
        System.out.println("Stats: " + stats4);
        System.out.println();

        // Performans karşılaştırması
        System.out.println("=== PERFORMANS KARŞILAŞTIRMASI ===");
        System.out.println("Test 1 (Tekrarlayan): " + stats1.compressionRatio + "%");
        System.out.println("Test 2 (Metin): " + stats2.compressionRatio + "%");
        System.out.println("Test 3 (ABAB): " + stats3.compressionRatio + "%");
        System.out.println("Test 4 (Rastgele): " + stats4.compressionRatio + "%");

        double averageRatio = (stats1.compressionRatio + stats2.compressionRatio +
                              stats3.compressionRatio + stats4.compressionRatio) / 4;
        System.out.println("Ortalama Sıkıştırma Oranı: " + String.format("%.2f%%", averageRatio));
    }
}
