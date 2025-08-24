import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;

/**
 * SmartRLE - Hibrit Sıkıştırma Algoritması
 *
 * Özellikler:
 * - Adaptive RLE (Gelişmiş tekrar algılama)
 * - Smart Dictionary (Akıllı sözlük)
 * - Context Prediction (Bağlam tahmini)
 * - Dynamic Threshold (Dinamik eşik)
 * - Self-Tuning (Otomatik optimizasyon)
 */
public class SmartRLE {

    private Map<String, String> dictionary;
    private Map<Character, Integer> frequencyMap;
    private List<String> commonPatterns;
    private int compressionLevel;
    private double threshold;
    // New reversible state
    private Map<String, String> patternHistory; // code -> original pattern
    private Map<String, String> lineTemplates;  // Lxx -> normalized line
    private List<String> timestamps;            // original timestamps
    private List<String> ips;                   // original IPs
    private List<String> uuids;                 // original UUIDs
    private List<String> ids;                   // original numeric IDs
    private List<String> apacheTimestamps;      // original Apache-style timestamps (fallback)
    private long apacheTsBaseEpoch;             // base epoch seconds for ATS
    private List<Integer> apacheTsDeltas;       // delta seconds for ATS
    private String apacheTsOffset;              // timezone offset like +0300
    // Field-level mappings (segment/global for now)
    private List<String> methods;
    private List<String> paths;
    private List<String> statuses;
    private List<String> referers;
    private List<String> userAgents;
    // Mapping guard flags (per segment)
    private boolean mapPaths;
    private boolean mapReferers;
    private boolean mapUserAgents;
    private Map<String, String> charMap;        // Cx -> original char
    private int nextPatternCode;                // counter for Pxx
    private int nextLineCode;                   // counter for Lxx
    private Set<String> usedDictCodes;          // which DICT codes were applied
    private String eol;                         // original line ending ("\n" or "\r\n")
    private boolean hasTrailingEol;             // original input had trailing EOL
    private static final String CODE_SENTINEL = "~"; // compact wraps to avoid collisions

    public SmartRLE() {
        this.dictionary = new HashMap<>();
        this.frequencyMap = new HashMap<>();
        this.commonPatterns = new ArrayList<>();
        this.compressionLevel = 1;
        this.threshold = 0.7;
        this.patternHistory = new LinkedHashMap<>();
        this.lineTemplates = new LinkedHashMap<>();
        this.timestamps = new ArrayList<>();
        this.ips = new ArrayList<>();
        this.uuids = new ArrayList<>();
        this.ids = new ArrayList<>();
        this.apacheTimestamps = new ArrayList<>();
        this.apacheTsBaseEpoch = -1L;
        this.apacheTsDeltas = new ArrayList<>();
        this.apacheTsOffset = null;
        this.methods = new ArrayList<>();
        this.paths = new ArrayList<>();
        this.statuses = new ArrayList<>();
        this.referers = new ArrayList<>();
        this.userAgents = new ArrayList<>();
        this.mapPaths = true;
        this.mapReferers = true;
        this.mapUserAgents = true;
        this.charMap = new LinkedHashMap<>();
        this.nextPatternCode = 0;
        this.nextLineCode = 0;
        this.usedDictCodes = new LinkedHashSet<>();
        this.eol = "\n";
        this.hasTrailingEol = false;
        initializeDictionary();
    }

    private void initializeDictionary() {
        // Sık kullanılan desenleri önceden yükle
        commonPatterns.addAll(Arrays.asList(
            "the", "and", "ing", "ion", "ent", "for", "you", "not",
            "are", "but", "had", "was", "one", "our", "her", "all"
        ));

        for (int i = 0; i < commonPatterns.size(); i++) {
            dictionary.put(commonPatterns.get(i), String.format("D%02d", i));
        }
    }

    /**
     * Ana sıkıştırma metodu
     */
    public String compress(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        resetState();

        // Stage 0: Detect line separator and preprocess (normalize timestamps/IP/UUID/IDs)
        this.eol = detectLineSeparator(input);
        this.hasTrailingEol = input.endsWith(this.eol);
        String preprocessed = preprocess(input);

        // Stage 1: Dictionary compression (log levels etc.)
        String dictCompressed = applyDictionaryCompression(preprocessed);

        // Stage 2: Token-LZ backref compression (debugging: disabled)
        String tokenLZCompressed = dictCompressed;

        // Stage 3: Token-block RLE compression (new)
        String tokenRLECompressed = applyTokenBlockRLE(tokenLZCompressed);

        // Stage 4: Pattern compression (conservative to limit header growth)
        String patternCompressed = applyPatternCompression(tokenRLECompressed);

        // Stage 5: Line-level coding (assign Lxx to each unique line)
        String lineCoded = applyLineCoding(patternCompressed);

        // Stage 6: RLE (ASCII, reversible). Apply on literal characters only as safe default.
        String rleCompressed = applyAdaptiveRLE(lineCoded);

        // Stage 7: Aggressive reversible char mapping (disabled to avoid expansion)
        String aggressiveCompressed = rleCompressed; // applyAggressiveCompressionReversible(rleCompressed);

        // Build header and serialize
        String header = buildHeader();
        header = maybeGzipHeader(header);
        StringBuilder out = new StringBuilder();
        out.append(header).append("\n[DATA]\n").append(aggressiveCompressed);
        return out.toString();
    }

    /**
     * Dictionary tabanlı sıkıştırma
     */
    private String applyDictionaryCompression(String input) {
        String result = input;
        for (Map.Entry<String, String> entry : dictionary.entrySet()) {
            String pattern = entry.getKey();
            String code = entry.getValue();
            // Case-sensitive whole-word match to avoid altering original casing
            Pattern p = Pattern.compile("\\b" + Pattern.quote(pattern) + "\\b");
            Matcher m = p.matcher(result);
            StringBuffer sb = new StringBuffer();
            boolean found = false;
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(wrapCode(code)));
                found = true;
            }
            m.appendTail(sb);
            if (found) {
                usedDictCodes.add(code);
                result = sb.toString();
            }
        }
        return result;
    }

    /**
     * Gelişmiş RLE algoritması
     */
    private String applyAdaptiveRLE(String input) {
        if (input.length() < 3) return input;

        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < input.length()) {
            char current = input.charAt(i);
            int count = 1;
            while (i + count < input.length() && input.charAt(i + count) == current) {
                count++;
            }
            if (count >= 6) {
                // ASCII-safe format: R:<char>:<count>;
                result.append("R:").append(escapeChar(current)).append(":").append(count).append(";");
                i += count;
            } else {
                // copy as-is for short runs
                result.append(current);
                i++;
            }
        }

        return result.toString();
    }

    /**
     * Pattern tabanlı sıkıştırma
     */
    private String applyPatternCompression(String input) {
        // Line-local conservative substring patterning to keep reversibility and avoid cross-line changes
        String[] lines = input.split("\n", -1);
        StringBuilder out = new StringBuilder();
        // Build reverse lookup for already assigned patterns
        Map<String, String> patternToCode = new HashMap<>();
        for (Map.Entry<String,String> e : patternHistory.entrySet()) {
            patternToCode.put(e.getValue(), e.getKey());
        }

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String result = line;
            int maxLen = Math.min(12, Math.max(5, Math.max(1, line.length() / 40)));
            for (int len = 5; len <= maxLen; len++) {
                Map<String, Integer> freq = new HashMap<>();
                for (int i = 0; i + len <= line.length(); i++) {
                    String sub = line.substring(i, i + len);
                    freq.put(sub, freq.getOrDefault(sub, 0) + 1);
                }
                List<Map.Entry<String, Integer>> candidates = new ArrayList<>(freq.entrySet());
                candidates.removeIf(e -> e.getValue() < 5);
                candidates.sort((a,b) -> Integer.compare(b.getValue(), a.getValue()));
                for (Map.Entry<String,Integer> e : candidates) {
                    String pattern = e.getKey();
                    if (patternHistory.size() >= 30) break;
                    if (containsControl(pattern)) continue;
                    String code = patternToCode.get(pattern);
                    if (code == null) {
                        code = nextPatternCode();
                        patternHistory.put(code, pattern);
                        patternToCode.put(pattern, code);
                    }
                    if (result.contains(pattern)) {
                        result = result.replace(pattern, wrapCode(code));
                    }
                }
                if (patternHistory.size() >= 30) break;
            }
            out.append(result);
            if (li < lines.length - 1) out.append("\n");
        }
        return out.toString();
    }

    /**
     * Desenin tekrar sayısını hesapla
     */
    private int countRepeats(String text, String pattern, int startIndex) {
        int count = 0;
        int index = startIndex;

        while (index <= text.length() - pattern.length()) {
            if (text.substring(index, index + pattern.length()).equals(pattern)) {
                count++;
                index += pattern.length();
            } else {
                break;
            }
        }

        return count;
    }

    /**
     * Son optimizasyon aşaması
     */
    private String optimizeCompression(String input) {
        // Basit optimizasyon - threshold kontrolü
        if (input.length() > 100 && compressionLevel < 3) {
            // Sıkıştırma yetersiz, daha agresif yaklaşım
            return applyAggressiveCompression(input);
        }

        return input;
    }

    /**
     * Agresif sıkıştırma (fallback)
     */
    private String applyAggressiveCompression(String input) {
        // kept for backward compatibility (unused in new pipeline)
        return input;
    }

    private String applyAggressiveCompressionReversible(String input) {
        Map<Character, Integer> charFreq = new HashMap<>();
        for (char c : input.toCharArray()) {
            charFreq.put(c, charFreq.getOrDefault(c, 0) + 1);
        }
        List<Map.Entry<Character, Integer>> sorted = new ArrayList<>(charFreq.entrySet());
        sorted.sort((a,b) -> Integer.compare(b.getValue(), a.getValue()));
        String result = input;
        int maxMap = 5;
        int mapped = 0;
        for (Map.Entry<Character,Integer> e : sorted) {
            if (mapped >= maxMap) break;
            char ch = e.getKey();
            // skip alphanumerics and control/syntax chars to avoid breaking format
            if (Character.isLetterOrDigit(ch)) continue;
            if (ch == '\n' || ch == '\r' || ch == '|' || ch == ':' || ch == ';' || ch == '\\') continue;
            String code = "C" + mapped;
            charMap.put(code, String.valueOf(ch));
            result = result.replace(String.valueOf(ch), code);
            mapped++;
        }
        return result;
    }

    /**
     * Sıkıştırma çözme metodu
     */
    public String decompress(String compressed) {
        if (compressed == null || compressed.isEmpty()) {
            return "";
        }

        int headerIdx = compressed.indexOf("\n[DATA]\n");
        if (headerIdx < 0) {
            // Backward compatibility: try old pipeline
            String result = compressed;
            result = decompressRLE(result);
            result = decompressDictionary(result);
            result = decompressPatterns(result);
            result = decompressCharacters(result);
            return result;
        }

        String headerText = compressed.substring(0, headerIdx);
        String data = compressed.substring(headerIdx + "\n[DATA]\n".length());

        // parse header into state
        parseHeader(headerText);

        // reverse aggressive char mapping
        for (Map.Entry<String,String> e : charMap.entrySet()) {
            data = data.replace(e.getKey(), e.getValue());
        }

        // reverse RLE
        data = decompressRLE(data);

        // reverse line codes
        data = decompressLineCoding(data);

        // reverse patterns
        data = decompressPatterns(data);

        // reverse token-block RLE (new)
        data = decompressTokenBlockRLE(data);

        // reverse token-LZ (debugging: disabled)
        // data = decompressTokenLZ(data);

        // reverse dictionary
        for (Map.Entry<String,String> e : dictionary.entrySet()) {
            data = data.replace(wrapCode(e.getValue()), e.getKey());
        }

        String result = data;

        // denormalize tokens (__TSi__, __IPi__, __UUIDi__, __IDi__)
        result = denormalize(result);

        // apply original EOL and trailing EOL policy
        result = applyEol(result);

        return result;
    }

    private String decompressRLE(String input) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            if (input.startsWith("R:", i)) {
                int c1 = input.indexOf(':', i + 2);
                int c2 = input.indexOf(';', i + 3);
                if (c1 > 0 && c2 > c1) {
                    String chEnc = input.substring(i + 2, c1);
                    int count = Integer.parseInt(input.substring(c1 + 1, c2));
                    char ch = unescapeChar(chEnc);
                    for (int k = 0; k < count; k++) out.append(ch);
                    i = c2 + 1;
                    continue;
                }
            }
            out.append(input.charAt(i));
            i++;
        }
        return out.toString();
    }

    private String decompressDictionary(String input) {
        String result = input;

        for (Map.Entry<String, String> entry : dictionary.entrySet()) {
            result = result.replace(wrapCode(entry.getValue()), entry.getKey());
        }

        return result;
    }

    private String decompressPatterns(String input) {
        // Using patternHistory if available; otherwise no-op
        String result = input;
        if (patternHistory != null) {
            for (Map.Entry<String,String> e : patternHistory.entrySet()) {
                result = result.replace(wrapCode(e.getKey()), e.getValue());
            }
        }
        return result;
    }

    private String decompressCharacters(String input) {
        String result = input;
        if (charMap != null) {
            for (Map.Entry<String,String> e : charMap.entrySet()) {
                result = result.replace(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    /**
     * Performans istatistikleri
     */
    public CompressionStats getStats(String original, String compressed) {
        double ratio = (double) compressed.length() / original.length() * 100;
        return new CompressionStats(original.length(), compressed.length(), ratio);
    }

    public static class CompressionStats {
        public final int originalSize;
        public final int compressedSize;
        public final double compressionRatio;

        public CompressionStats(int original, int compressed, double ratio) {
            this.originalSize = original;
            this.compressedSize = compressed;
            this.compressionRatio = ratio;
        }

        @Override
        public String toString() {
            return String.format("Original: %d bytes, Compressed: %d bytes, Ratio: %.2f%%",
                              originalSize, compressedSize, compressionRatio);
        }
    }

    // ===== Binary token stream scaffolding (phase 1) =====
    private static final class Varint {
        static void writeVarint(ByteArrayOutputStream out, int value) {
            // Unsigned LEB128 (value assumed >= 0)
            int v = value;
            do {
                int b = v & 0x7F;
                v >>>= 7;
                if (v != 0) b |= 0x80;
                out.write(b);
            } while (v != 0);
        }

        static int readVarint(byte[] data, int[] posRef) {
            int pos = posRef[0];
            int result = 0;
            int shift = 0;
            while (pos < data.length) {
                int b = data[pos++] & 0xFF;
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            posRef[0] = pos;
            return result;
        }
    }

    // Opcodes reserved for future binary token stream
    private static final int OP_LITERAL_STR = 0xF0;
    private static final int OP_BACKREF     = 0xF1;
    private static final int OP_RLE_BLOCK   = 0xF2;
    private static final int OP_END         = 0xFF;

    private static final class TokenStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);

        void writeLiteralString(String s) {
            if (s == null) s = "";
            byte[] utf8 = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buffer.write(OP_LITERAL_STR);
            Varint.writeVarint(buffer, utf8.length);
            buffer.write(utf8, 0, utf8.length);
        }

        void writeBackref(int length, int distance) {
            buffer.write(OP_BACKREF);
            Varint.writeVarint(buffer, length);
            Varint.writeVarint(buffer, distance);
        }

        void writeRleBlock(int length, String literal) {
            buffer.write(OP_RLE_BLOCK);
            Varint.writeVarint(buffer, length);
            writeLiteralString(literal == null ? "" : literal);
        }

        byte[] finish() {
            buffer.write(OP_END);
            return buffer.toByteArray();
        }
    }

    // ===== New helpers for log-specific pipeline =====

    private void resetState() {
        patternHistory.clear();
        lineTemplates.clear();
        timestamps.clear();
        ips.clear();
        uuids.clear();
        ids.clear();
        charMap.clear();
        nextPatternCode = 0;
        nextLineCode = 0;
    }

    private String preprocess(String input) {
        String[] lines = input.split("\r?\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String norm = normalizeLine(lines[i]);
            out.append(norm);
            if (i < lines.length - 1) out.append("\n");
        }
        return out.toString();
    }

    private static final Pattern TS_YMD_HMS = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:,\\d{3})?\\b");
    private static final Pattern APACHE_TS = Pattern.compile("\\[(\\d{2}/[A-Za-z]{3}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4})\\]");
    private static final DateTimeFormatter APACHE_FMT = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
    private static final Pattern APACHE_COMBINED = Pattern.compile(
        "^(\\S+) (\\S+) (\\S+) \\[(.*?)\\] \"(\\S+)\\s+(\\S+)(?:\\s+(HTTP/\\d+\\.\\d+))?\" (\\d{3}) (\\S+)(?: \"([^\"]*)\" \"([^\"]*)\")?.*$"
    );
    private static final int MAX_PATHS = 5000;
    private static final int MAX_UA = 1000;
    private static final int MAX_REF = 1000;
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern UUID_RE = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b");
    private static final Pattern LONG_ID = Pattern.compile("\\b\\d{6,}\\b");

    private String normalizeLine(String line) {
        String n = line;
        // Try Apache combined log parse
        try {
            Matcher m = APACHE_COMBINED.matcher(n);
            if (m.matches()) {
                String ip = m.group(1);
                String ident = m.group(2);
                String user = m.group(3);
                String ts = m.group(4); // handled below by ATS normalization
                String method = m.group(5);
                String path = m.group(6);
                String httpVer = m.group(7) == null ? null : m.group(7);
                String status = m.group(8);
                String size = m.group(9);
                String ref = m.group(10) == null ? "-" : m.group(10);
                String ua = m.group(11) == null ? "-" : m.group(11);

                // map IP
                Matcher mIpOnly = IPV4.matcher(ip);
                if (mIpOnly.matches()) {
                    int idxIp = ips.size();
                    ips.add(ip);
                    ip = "__IP" + idxIp + "__";
                }

                // map method/status
                int idxM = methods.indexOf(method);
                if (idxM < 0) { idxM = methods.size(); methods.add(method); }
                String methodTok = "__METH" + idxM + "__";

                int idxS = statuses.indexOf(status);
                if (idxS < 0) { idxS = statuses.size(); statuses.add(status); }
                String statusTok = "__STAT" + idxS + "__";

                // path (guardrail)
                String pathTok = path;
                if (mapPaths) {
                    int idxP = paths.indexOf(path);
                    if (idxP < 0 && paths.size() < MAX_PATHS) { idxP = paths.size(); paths.add(path); }
                    if (idxP >= 0) pathTok = "__PATH" + idxP + "__";
                }

                // referer / UA (guardrail)
                String refTok = ref;
                if (mapReferers) {
                    int idxR = referers.indexOf(ref);
                    if (idxR < 0 && referers.size() < MAX_REF) { idxR = referers.size(); referers.add(ref); }
                    if (idxR >= 0) refTok = "__REF" + idxR + "__";
                }

                String uaTok = ua;
                if (mapUserAgents) {
                    int idxU = userAgents.indexOf(ua);
                    if (idxU < 0 && userAgents.size() < MAX_UA) { idxU = userAgents.size(); userAgents.add(ua); }
                    if (idxU >= 0) uaTok = "__UA" + idxU + "__";
                }

                // rebuild normalized line preserving ident/user and HTTP version
                StringBuilder nb = new StringBuilder();
                nb.append(ip).append(' ').append(ident).append(' ').append(user).append(' ');
                nb.append('[').append(ts).append("] ");
                nb.append('"').append(methodTok).append(' ').append(pathTok);
                if (httpVer != null) { nb.append(' ').append(httpVer); }
                nb.append('"').append(' ');
                nb.append(statusTok).append(' ').append(size);
                nb.append(' ');
                nb.append('"').append(refTok).append('"').append(' ').append('"').append(uaTok).append('"');
                n = nb.toString();
            }
        } catch (Exception ignore) {}
        // Apache timestamp [dd/Mon/yyyy:HH:mm:ss +/-zzzz] -> __ATSi__ with base+delta capture
        Matcher mAts = APACHE_TS.matcher(n);
        StringBuffer sbAts = new StringBuffer();
        while (mAts.find()) {
            String full = mAts.group(0); // includes brackets
            String tsText = mAts.group(1); // inner timestamp
            int idx = apacheTimestamps.size();
            apacheTimestamps.add(full);
            try {
                // parse inner tsText to epoch seconds and offset
                ZonedDateTime zdt = ZonedDateTime.parse(tsText.replace(" ", "+0000 ").substring(0, tsText.length()), APACHE_FMT);
            } catch (Exception ex) {
                // Fallback: parse using formatter directly
            }
            try {
                ZonedDateTime zdt2 = ZonedDateTime.parse(tsText, APACHE_FMT);
                long epoch = zdt2.toEpochSecond();
                String off = tsText.substring(tsText.length()-5);
                if (apacheTsBaseEpoch < 0) {
                    apacheTsBaseEpoch = epoch;
                    apacheTsOffset = off;
                }
                int delta = (int)(epoch - apacheTsBaseEpoch);
                apacheTsDeltas.add(delta);
            } catch (Exception ignore) {
                // if parse fails, we still have fallback list
                apacheTsDeltas.add(0);
                if (apacheTsBaseEpoch < 0) apacheTsBaseEpoch = 0L;
                if (apacheTsOffset == null) apacheTsOffset = "+0000";
            }
            mAts.appendReplacement(sbAts, Matcher.quoteReplacement("__ATS" + idx + "__"));
        }
        mAts.appendTail(sbAts);
        n = sbAts.toString();
        // timestamps -> __TSi__
        Matcher mTs = TS_YMD_HMS.matcher(n);
        StringBuffer sb = new StringBuffer();
        while (mTs.find()) {
            String val = mTs.group();
            int idx = timestamps.size();
            timestamps.add(val);
            mTs.appendReplacement(sb, Matcher.quoteReplacement("__TS" + idx + "__"));
        }
        mTs.appendTail(sb);
        n = sb.toString();

        // IPv4 -> __IPi__
        Matcher mIp = IPV4.matcher(n);
        sb = new StringBuffer();
        while (mIp.find()) {
            String val = mIp.group();
            int idx = ips.size();
            ips.add(val);
            mIp.appendReplacement(sb, Matcher.quoteReplacement("__IP" + idx + "__"));
        }
        mIp.appendTail(sb);
        n = sb.toString();

        // UUID -> __UUIDi__
        Matcher mUuid = UUID_RE.matcher(n);
        sb = new StringBuffer();
        while (mUuid.find()) {
            String val = mUuid.group();
            int idx = uuids.size();
            uuids.add(val);
            mUuid.appendReplacement(sb, Matcher.quoteReplacement("__UUID" + idx + "__"));
        }
        mUuid.appendTail(sb);
        n = sb.toString();

        // long numeric IDs -> __IDi__
        Matcher mId = LONG_ID.matcher(n);
        sb = new StringBuffer();
        while (mId.find()) {
            String val = mId.group();
            int idx = ids.size();
            ids.add(val);
            mId.appendReplacement(sb, Matcher.quoteReplacement("__ID" + idx + "__"));
        }
        mId.appendTail(sb);
        n = sb.toString();

        return n;
    }

    private String applyLineCoding(String input) {
        String[] lines = input.split("\n", -1);
        // First pass: count frequencies
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String l : lines) {
            freq.put(l, freq.getOrDefault(l, 0) + 1);
        }
        // Assign codes only to lines with freq >= 2
        Map<String, String> lineToCode = new HashMap<>();
        for (Map.Entry<String,Integer> e : freq.entrySet()) {
            if (e.getValue() >= 2) {
                String code = nextLineCode();
                String wrapped = wrapCode(code);
                lineTemplates.put(wrapped, e.getKey());
                lineToCode.put(e.getKey(), wrapped);
            }
        }

        StringBuilder out = new StringBuilder();
        String prevToken = null;
        int run = 0;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            String token = lineToCode.getOrDefault(l, l); // use code if assigned, else raw line
            if (prevToken == null) {
                prevToken = token;
                run = 1;
            } else if (token.equals(prevToken)) {
                run++;
            } else {
                flushLineRun(out, prevToken, run);
                prevToken = token;
                run = 1;
            }
        }
        if (prevToken != null) flushLineRun(out, prevToken, run);
        return out.toString();
    }

    private void flushLineRun(StringBuilder out, String token, int run) {
        if (run >= 2) {
            out.append("R|").append(token).append("|").append(run).append("|\n");
        } else {
            out.append(token).append("\n");
        }
    }

    private String lineCodeFor(String line) {
        for (Map.Entry<String,String> e : lineTemplates.entrySet()) {
            if (e.getValue().equals(line)) return e.getKey();
        }
        String code = nextLineCode();
        String wrapped = wrapCode(code);
        lineTemplates.put(wrapped, line);
        return wrapped;
    }

    private String nextPatternCode() {
        String c = String.format("P%02d", nextPatternCode);
        nextPatternCode++;
        return c;
    }

    private String nextLineCode() {
        String c = String.format("L%02d", nextLineCode);
        nextLineCode++;
        return c;
    }

    private String buildHeader() {
        StringBuilder h = new StringBuilder();
        h.append("[SMARTRLE_HEADER]\n");
        h.append("VERSION:SmartRLEv1-log\n");
        h.append("EOL:").append("\r\n".equals(eol) ? "CRLF" : "LF").append("\n");
        h.append("TRAIL:").append(hasTrailingEol ? "1" : "0").append("\n");
        if (apacheTsBaseEpoch >= 0) {
            h.append("ATSBASE:").append(apacheTsBaseEpoch).append("\n");
            h.append("ATSOFFSET:").append(apacheTsOffset == null ? "" : apacheTsOffset).append("\n");
            h.append("ATSDELTA:").append(joinIntList(apacheTsDeltas)).append("\n");
        }
        // Guardrail: if these sections are too large, skip mapping in this segment
        int approxBefore = h.length();
        int approxAfter;
        // lists
        h.append("TS:").append(joinList(timestamps)).append("\n");
        if (!(apacheTsBaseEpoch >= 0 && apacheTsOffset != null && apacheTsDeltas != null && !apacheTsDeltas.isEmpty())) {
            h.append("ATS:").append(joinList(apacheTimestamps)).append("\n");
        }
        if (mapPaths) h.append("PATH:").append(joinList(paths)).append("\n");
        if (mapReferers) h.append("REF:").append(joinList(referers)).append("\n");
        if (mapUserAgents) h.append("UA:").append(joinList(userAgents)).append("\n");
        h.append("METH:").append(joinList(methods)).append("\n");
        h.append("STAT:").append(joinList(statuses)).append("\n");
        h.append("IP:").append(joinList(ips)).append("\n");
        h.append("UUID:").append(joinList(uuids)).append("\n");
        h.append("ID:").append(joinList(ids)).append("\n");
        approxAfter = h.length();
        // if header grew too much, disable heavy mappings next segment
        int grown = approxAfter - approxBefore;
        if (grown > 8192) { // 8KB guardrail
            mapPaths = false; mapReferers = false; mapUserAgents = false;
        }
        // dictionary (only used codes)
        if (!usedDictCodes.isEmpty()) {
            // reverse lookup word by code
            Map<String,String> codeToWord = new HashMap<>();
            for (Map.Entry<String,String> e : dictionary.entrySet()) {
                codeToWord.put(e.getValue(), e.getKey());
            }
            for (String code : usedDictCodes) {
                String word = codeToWord.get(code);
                if (word != null) {
                    h.append("DICT:").append(code).append("=").append(word).append("\n");
                }
            }
        }
        // patterns
        for (Map.Entry<String,String> e : patternHistory.entrySet()) {
            h.append("PAT:").append(e.getKey()).append("=").append(escapeLine(e.getValue())).append("\n");
        }
        // lines
        for (Map.Entry<String,String> e : lineTemplates.entrySet()) {
            h.append("LCODE:").append(e.getKey()).append("=").append(escapeLine(e.getValue())).append("\n");
        }
        // char map
        for (Map.Entry<String,String> e : charMap.entrySet()) {
            h.append("CHAR:").append(e.getKey()).append("=").append(escapeCharStr(e.getValue())).append("\n");
        }
        return h.toString();
    }

    private void parseHeader(String header) {
        resetState();
        if (header.startsWith("[SMARTRLE_HEADERGZ]")) {
            String[] lines0 = header.split("\n");
            String b64 = null;
            for (String l : lines0) {
                if (l.startsWith("B64:")) { b64 = l.substring(4).trim(); break; }
            }
            if (b64 != null && !b64.isEmpty()) {
                String decoded = gunzipFromBase64(b64);
                parseHeaderPlain(decoded);
                return;
            }
        }
        parseHeaderPlain(header);
    }

    private void parseHeaderPlain(String header) {
        String[] lines = header.split("\n");
        for (String l : lines) {
            if (l.startsWith("DICT:")) {
                String[] kv = l.substring(5).split("=", 2);
                if (kv.length == 2) {
                    String code = kv[0];
                    String word = kv[1];
                    // reverse mapping for replacement usage
                    dictionary.put(word, code);
                }
            } else if (l.startsWith("PAT:")) {
                String[] kv = l.substring(4).split("=", 2);
                if (kv.length == 2) {
                    patternHistory.put(kv[0], unescapeLine(kv[1]));
                }
            } else if (l.startsWith("LCODE:")) {
                String[] kv = l.substring(6).split("=", 2);
                if (kv.length == 2) {
                    lineTemplates.put(kv[0], unescapeLine(kv[1]));
                }
            } else if (l.startsWith("CHAR:")) {
                String[] kv = l.substring(5).split("=", 2);
                if (kv.length == 2) {
                    charMap.put(kv[0], unescapeCharStr(kv[1]));
                }
            } else if (l.startsWith("TS:")) {
                timestamps.addAll(splitList(l.substring(3)));
            } else if (l.startsWith("ATS:")) {
                apacheTimestamps.addAll(splitList(l.substring(4)));
            } else if (l.startsWith("ATSBASE:")) {
                try { this.apacheTsBaseEpoch = Long.parseLong(l.substring(8).trim()); } catch (Exception ignore) {}
            } else if (l.startsWith("ATSOFFSET:")) {
                this.apacheTsOffset = l.substring(10).trim();
            } else if (l.startsWith("ATSDELTA:")) {
                this.apacheTsDeltas = splitIntList(l.substring(9));
            } else if (l.startsWith("METH:")) {
                methods.addAll(splitList(l.substring(5)));
            } else if (l.startsWith("PATH:")) {
                paths.addAll(splitList(l.substring(5)));
            } else if (l.startsWith("STAT:")) {
                statuses.addAll(splitList(l.substring(5)));
            } else if (l.startsWith("REF:")) {
                referers.addAll(splitList(l.substring(4)));
            } else if (l.startsWith("UA:")) {
                userAgents.addAll(splitList(l.substring(3)));
            } else if (l.startsWith("IP:")) {
                ips.addAll(splitList(l.substring(3)));
            } else if (l.startsWith("UUID:")) {
                uuids.addAll(splitList(l.substring(5)));
            } else if (l.startsWith("ID:")) {
                ids.addAll(splitList(l.substring(3)));
            } else if (l.startsWith("EOL:")) {
                String v = l.substring(4).trim();
                this.eol = "CRLF".equals(v) ? "\r\n" : "\n";
            } else if (l.startsWith("TRAIL:")) {
                String v = l.substring(6).trim();
                this.hasTrailingEol = "1".equals(v) || "true".equalsIgnoreCase(v);
            }
        }
    }

    private String joinList(List<String> list) {
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(escapeListItem(list.get(i)));
        }
        return sb.toString();
    }

    private List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        int i = 0;
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (esc) {
                cur.append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
            i++;
        }
        out.add(cur.toString());
        return out;
    }

    private String escapeLine(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n");
    }
    private String unescapeLine(String s) {
        StringBuilder out = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                if (c == 'n') out.append('\n'); else out.append(c);
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String escapeChar(char c) { return escapeCharStr(String.valueOf(c)); }
    private String escapeCharStr(String s) {
        if (s == null || s.isEmpty()) return "";
        char c = s.charAt(0);
        if (c == '\n') return "\\n";
        if (c == '\r') return "\\r";
        if (c == '|' || c == ':' || c == ';' || c == '\\') return "\\" + c;
        return String.valueOf(c);
    }
    private String unescapeCharStr(String s) {
        if (s == null) return "";
        if (s.equals("\\n")) return "\n";
        if (s.equals("\\r")) return "\r";
        if (s.startsWith("\\") && s.length() == 2) return String.valueOf(s.charAt(1));
        return s;
    }
    private char unescapeChar(String s) {
        String u = unescapeCharStr(s);
        return u.isEmpty() ? '\u0000' : u.charAt(0);
    }

    private String denormalize(String input) {
        String out = input;
        // TS
        out = replaceIndexed(out, "__TS", "__", timestamps);
        // Prefer base+delta if available; fallback to stored list
        if (apacheTsBaseEpoch >= 0 && apacheTsOffset != null && apacheTsDeltas != null && !apacheTsDeltas.isEmpty()) {
            // reconstruct bracketed apache timestamps
            List<String> rebuilt = new ArrayList<>(apacheTsDeltas.size());
            for (int i = 0; i < apacheTsDeltas.size(); i++) {
                long epoch = apacheTsBaseEpoch + apacheTsDeltas.get(i);
                ZonedDateTime zdt = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.of(apacheTsOffset));
                String tsInner = zdt.format(APACHE_FMT);
                rebuilt.add("[" + tsInner + "]");
            }
            out = replaceIndexed(out, "__ATS", "__", rebuilt);
        } else {
            out = replaceIndexed(out, "__ATS", "__", apacheTimestamps);
        }
        out = replaceIndexed(out, "__IP", "__", ips);
        out = replaceIndexed(out, "__METH", "__", methods);
        out = replaceIndexed(out, "__PATH", "__", paths);
        out = replaceIndexed(out, "__STAT", "__", statuses);
        out = replaceIndexed(out, "__REF", "__", referers);
        out = replaceIndexed(out, "__UA", "__", userAgents);
        out = replaceIndexed(out, "__UUID", "__", uuids);
        out = replaceIndexed(out, "__ID", "__", ids);
        return out;
    }

    private String replaceIndexed(String s, String prefix, String suffix, List<String> values) {
        Pattern p = Pattern.compile(Pattern.quote(prefix) + "(\\d+)" + Pattern.quote(suffix));
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            String repl = (idx >= 0 && idx < values.size()) ? values.get(idx) : "";
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String joinIntList(List<Integer> list) {
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private List<Integer> splitIntList(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        String[] parts = s.split(",");
        for (String p : parts) {
            try { out.add(Integer.parseInt(p.trim())); } catch (Exception ignore) {}
        }
        return out;
    }

    private String detectLineSeparator(String s) {
        int idx = s.indexOf('\n');
        if (idx > 0 && idx - 1 >= 0 && s.charAt(idx - 1) == '\r') return "\r\n";
        return "\n";
    }

    private String applyEol(String s) {
        String tmp = s;
        // First ensure we have proper EOL format
        if ("\r\n".equals(eol)) {
            tmp = tmp.replace("\n", "\r\n");
        }
        // Then handle trailing EOL policy
        if (!hasTrailingEol && tmp.endsWith(eol)) {
            tmp = tmp.substring(0, tmp.length() - eol.length());
        } else if (hasTrailingEol && !tmp.endsWith(eol)) {
            tmp = tmp + eol;
        }
        return tmp;
    }

    private String wrapCode(String code) {
        return CODE_SENTINEL + code + CODE_SENTINEL;
    }

    // --- Missing helpers for lint fixes ---
    private boolean containsControl(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127) return true;
            if (c == '|' || c == ':' || c == ';' || c == '\n') return true;
        }
        return false;
    }

    private String escapeListItem(String s) {
        if (s == null) return "";
        String out = s.replace("\\", "\\\\");
        out = out.replace(",", "\\,");
        out = out.replace("\n", "\\n");
        return out;
    }

    private String maybeGzipHeader(String header) {
        if (header.length() < 1024) return header;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
                gos.write(header.getBytes(StandardCharsets.UTF_8));
            }
            String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            StringBuilder sb = new StringBuilder();
            sb.append("[SMARTRLE_HEADERGZ]\n");
            sb.append("B64:").append(b64).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return header;
        }
    }

    private String gunzipFromBase64(String b64) {
        try {
            byte[] data = Base64.getDecoder().decode(b64);
            GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
            BufferedReader br = new BufferedReader(new InputStreamReader(gis, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            if (sb.length() > 0) sb.setLength(sb.length()-1);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // Token-LZ implementation with sliding window
    private String applyTokenLZ(String input) {
        // For now, simple line-exact matching only to avoid EOL issues
        String[] lines = input.split("\n", -1); // preserve empty strings
        if (lines.length <= 1) return input; // not enough lines
        
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // Look for exact line matches in sliding window
            boolean foundMatch = false;
            int maxDist = Math.min(i, 64); // smaller window for efficiency
            
            for (int dist = 1; dist <= maxDist; dist++) {
                int backIdx = i - dist;
                if (backIdx >= 0 && lines[backIdx].equals(line)) {
                    // Found exact line match
                    result.append("Z").append(dist).append(";");
                    foundMatch = true;
                    break;
                }
            }
            
            if (!foundMatch) {
                // No match found, emit literal
                result.append("L").append(escapeLine(line)).append(";");
            }
        }
        
        return result.toString();
    }
    
    private boolean findTokenLZMatch(String[] tokens, String[] lines, int currentIdx, StringBuilder result) {
        int minLen = 3; // minimum token sequence length
        int maxLen = Math.min(tokens.length, 8);
        
        for (int len = maxLen; len >= minLen; len--) {
            for (int start = 0; start <= tokens.length - len; start++) {
                String[] pattern = Arrays.copyOfRange(tokens, start, start + len);
                
                // Look for this pattern in previous lines
                int maxDist = Math.min(currentIdx, 128);
                for (int dist = 1; dist <= maxDist; dist++) {
                    int backIdx = currentIdx - dist;
                    if (backIdx >= 0) {
                        String[] backTokens = lines[backIdx].split("\\s+");
                        int matchPos = findTokenSequence(backTokens, pattern);
                        if (matchPos >= 0) {
                            // Found match: emit before, match, after
                            if (start > 0) {
                                String before = String.join(" ", Arrays.copyOfRange(tokens, 0, start));
                                result.append("L").append(escapeLine(before)).append(";");
                            }
                            result.append("T").append(dist).append(":").append(matchPos).append(":").append(len).append(";");
                            if (start + len < tokens.length) {
                                String after = String.join(" ", Arrays.copyOfRange(tokens, start + len, tokens.length));
                                result.append("L").append(escapeLine(after)).append(";");
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    
    private int findTokenSequence(String[] haystack, String[] needle) {
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (!haystack[i + j].equals(needle[j])) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }
    
    // Decompress Token-LZ
    private String decompressTokenLZ(String input) {
        String[] codes = input.split(";");
        List<String> lines = new ArrayList<>();
        
        for (String code : codes) {
            if (code.isEmpty()) continue;
            
            if (code.startsWith("L")) {
                // Literal line
                lines.add(unescapeLine(code.substring(1)));
            } else if (code.startsWith("Z")) {
                // Full line back-reference
                try {
                    int dist = Integer.parseInt(code.substring(1));
                    int backIdx = lines.size() - dist;
                    if (backIdx >= 0 && backIdx < lines.size()) {
                        lines.add(lines.get(backIdx));
                    } else {
                        lines.add(""); // fallback
                    }
                } catch (Exception e) {
                    lines.add(""); // fallback on parse error
                }
            }
        }
        
        return String.join("\n", lines);
    }
    
    // Apply token-block RLE on repeated sequences
    private String applyTokenBlockRLE(String input) {
        String[] lines = input.split("\n");
        StringBuilder result = new StringBuilder();
        
        int i = 0;
        while (i < lines.length) {
            String currentLine = lines[i];
            int count = 1;
            
            // Count consecutive identical lines
            while (i + count < lines.length && lines[i + count].equals(currentLine)) {
                count++;
            }
            
            if (count >= 4) { // RLE threshold for lines
                result.append("B").append(count).append(":").append(escapeLine(currentLine)).append(";");
                i += count;
            } else {
                result.append("S").append(escapeLine(currentLine)).append(";");
                i++;
            }
        }
        
        return result.toString();
    }
    
    // Decompress token-block RLE
    private String decompressTokenBlockRLE(String input) {
        String[] codes = input.split(";");
        List<String> lines = new ArrayList<>();
        
        for (String code : codes) {
            if (code.isEmpty()) continue;
            
            if (code.startsWith("B")) {
                // Block RLE: Bcount:line
                int colonIdx = code.indexOf(':', 1);
                if (colonIdx > 0) {
                    int count = Integer.parseInt(code.substring(1, colonIdx));
                    String line = unescapeLine(code.substring(colonIdx + 1));
                    for (int j = 0; j < count; j++) {
                        lines.add(line);
                    }
                }
            } else if (code.startsWith("S")) {
                // Single line
                lines.add(unescapeLine(code.substring(1)));
            }
        }
        
        return String.join("\n", lines);
    }
    
    // Decompress line coding
    private String decompressLineCoding(String input) {
        StringBuilder rebuilt = new StringBuilder();
        String[] tokens = input.split("\n");
        
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            if (t.startsWith("R|") && t.endsWith("|")) {
                // R|Lxx|count|
                String body = t.substring(2, t.length() - 1);
                String[] parts = body.split("\\|");
                if (parts.length == 2) {
                    String code = parts[0];
                    int count = Integer.parseInt(parts[1]);
                    String line = lineTemplates.getOrDefault(code, "");
                    for (int i = 0; i < count; i++) {
                        rebuilt.append(line).append("\n");
                    }
                    continue;
                }
            }
            String line = t;
            if (lineTemplates.containsKey(line)) {
                rebuilt.append(lineTemplates.get(line)).append("\n");
            } else {
                rebuilt.append(line).append("\n");
            }
        }
        
        return rebuilt.toString();
    }
    


    // Test metodu
    public static void main(String[] args) {
        SmartRLE compressor = new SmartRLE();

        String testText = "Hello world! This is a test text with some repeating patterns. " +
                         "This test text will be compressed using SmartRLE algorithm.";

        System.out.println("Original: " + testText);
        System.out.println("Length: " + testText.length());

        String compressed = compressor.compress(testText);
        System.out.println("Compressed: " + compressed);
        System.out.println("Length: " + compressed.length());

        String decompressed = compressor.decompress(compressed);
        System.out.println("Decompressed: " + decompressed);

        CompressionStats stats = compressor.getStats(testText, compressed);
        System.out.println("Stats: " + stats);
    }
}
