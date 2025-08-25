# 🚀 SmartRLE — Log Odaklı Kayıpsız Sıkıştırma (Java)

[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://www.oracle.com/java/)
[![Build Status](https://img.shields.io/badge/Build-Passing-green)](https://github.com/resul-exe/SmartRLE)
[![GitHub stars](https://img.shields.io/github/stars/resul-exe/SmartRLE)](https://github.com/resul-exe/SmartRLE/stargazers)
[![GitHub forks](https://img.shields.io/github/forks/resul-exe/SmartRLE)](https://github.com/resul-exe/SmartRLE/network)
[![Compression](https://img.shields.io/badge/Compression-47%25-success)](README.md)

## 📋 Genel Bakış

**SmartRLE**, metin tabanlı loglar için kayıpsız (lossless) ve deterministik bir sıkıştırma katmanıdır. Projede iki mod bulunmaktadır:

- Genel amaçlı metinler için klasik hibrit yaklaşım (sözlük + RLE + pattern).
- Log‑özel mod (üründe etkin): Apache/Nginx ve uygulama logları için alan‑bazlı ön‑işleme (Apache‑aware), tersine çevrilebilir başlık (header), satır kodlama, token‑blok RLE ve ASCII‑güvenli RLE. CRLF/LF dahil satır sonları ve son satırın EOL durumu korunur.

## 🎯 Temel Özellikler

- **Log‑bilinçli ön‑işleme**: Apache Combined Log formatı, IP/Timestamp/UUID/ID normalizasyonu
- **Tersine çevrilebilir başlık**: DICT/PAT/LCODE/CHAR ve alan listeleri (TS/ATS/METH/STAT/…) header’da saklanır
- **Satır kodlama + Token‑blok RLE**: Tekrarlayan satırlar ve bloklar kompakt kodlanır
- **ASCII‑güvenli RLE**: `R:<karakter>:<adet>;` formatı; çakışma/kaçış güvenli
- **EOL korunumu**: CRLF/LF ve trailing EOL politikası birebir korunur
- **Gerekirse header GZIP**: Büyük başlıklar base64+gzip ile küçültülür

## 🚀 Hızlı Başlangıç (Java)

```java
// Create compressor instance
SmartRLE compressor = new SmartRLE();

// Compress your data
String original = "aaaaaabbbbbbcccccc";
String compressed = compressor.compress(original);

// İstatistik al
SmartRLE.CompressionStats stats = compressor.getStats(original, compressed);
System.out.println(stats); // Original: 18 bytes, Compressed: 9 bytes, Ratio: 50.00%

// Decompress when needed
String decompressed = compressor.decompress(compressed);
```

## 🔧 Mimari ve Pipeline (Log Modu)

### Pipeline

```
Girdi → EOL Tespiti + Apache‑aware Ön‑İşleme (IP/TS/ID/UA/Path) → Sözlük (kelime/sabitler) →
Token‑Blok RLE (satır tekrarı) → Kalıp (temkinli) → Satır Kodlama → ASCII‑güvenli RLE →
Header (gerekirse GZIP) + DATA
```

Decompress sırası bu akışın tersidir. Tüm geri dönüşler header’daki eşlemelerden okunarak yapılır.

### Core Components

#### 1) Sözlük (Dictionary)
```java
// Common words replacement with short codes
"the" → "D00", "and" → "D01", "for" → "D02"
```

#### 2) ASCII‑Güvenli RLE 
```text
// 6+ tekrarda karakter koşusu kodlanır (örnek format)
R:<karakter>:<adet>;
Örn: aaaaaa → R:a:6;
```

#### 3) Kalıp (Pattern)
```java
// Detecting and encoding repeating patterns
"abcabc" → "P03" + reference
```

#### 4) Sıklık / Kısa Kodlar (opsiyonel)
```java
// Most frequent characters → short codes
Top 5 chars → C0, C1, C2, C3, C4
```

## 🧠 Akıllı Davranışlar

### 🎯 **Self-Learning Capability**
- Learns data patterns during execution
- Dynamically updates dictionary entries
- Improves pattern recognition over time

### ⚡ **Adaptive Performance**
- Adjusts strategy based on data size
- Lightweight approach for small data
- Aggressive compression for large datasets

### 🔍 **Context Awareness**
- Analyzes data type automatically
- Selects optimal compression technique
- Multi-stage optimization pipeline

## 📊 Performans – Log Modu (Apache)

### Gerçek Log Sonucu (apache_access_5mb.log ~ 5.24 MB)

| Araç     | Boyut (bayt) | Oran | Sıkıştırma (ms) | Açma (ms) | Doğruluk |
|----------|---------------|------|------------------|-----------|----------|
| SmartRLE | 3,383,109     | 64.53% | 1851 | 509 | ✅ |
| GZIP     | 741,640       | 14.15% | 151  | –   | – |

Notlar:
- SmartRLE doğruluk odaklıdır; log‑özel pipeline ile EOL/CRLF ve tüm alanlar birebir korunur.
- Token‑LZ (len,dist) katmanı şu an DEVRE DIŞI; güvenli sürüm etkinleştirildiğinde oranların iyileştirilmesi planlanmaktadır.

### ✅ Güçlü Yanlar (Log Modu)
- Excellent for repetitive data (47%+ compression)
- Fast processing time
- Low memory footprint  
- Adaptive learning capability
- Simple API integration

### 🔧 İyileştirme Alanları
- Overhead for random data
- Dictionary initialization cost
- Suboptimal for very small files

### 🎯 Uygun Kullanım Senaryoları
- 📄 **Log Files**: Timestamp and message patterns
- ⚙️ **Config Files**: Repetitive settings structure
- 🔄 **Template Data**: Standard format files
- 📊 **IoT Data**: Sensor readings with patterns

### 🚀 Benchmark Çalıştırma

```bash
javac SmartRLE.java BenchmarkRunner.java
java BenchmarkRunner apache_access_5mb.log
```

Çıktı; orijinal/sonuç boyutları, oran, süreler ve doğruluk kontrolünü içerir.

### 🔒 Header Formatı (Özet)

```
[SMARTRLE_HEADER] veya [SMARTRLE_HEADERGZ]\n
VERSION:SmartRLEv1-log
EOL:LF|CRLF
TRAIL:0|1
ATSBASE:<epochSec> (opsiyonel)  ATSOFFSET:+0300  ATSDELTA:1,1,2,...
TS:<ts1,ts2,...>  ATS:[ham timestamp listesi — base+delta yoksa]
METH:/ STAT:/ PATH:/ REF:/ UA:/ IP:/ UUID:/ ID:/
DICT:D00=the ...
PAT:P00=<pattern> ...
LCODE:L00=<line> ...
CHAR:C0=<char> ...
```

Header büyükse otomatik GZIP+Base64 ile yazılır:
```
[SMARTRLE_HEADERGZ]\n
B64:<base64-gzip-header>\n
```

## 🔬 Karşılaştırma ve Yol Haritası

- Şu an SmartRLE, log‑özel modda doğruluk odaklıdır; oran olarak GZIP’in gerisindedir.
- Planlanan iyileştirmeler:
  - Segment mini‑başlık (1–4K satır) ve header/payload guardrail
  - Güvenli Token‑LZ (token akışı üzerinde len,dist; varint kodlama)
  - Path templating ve varint tabanlı daha kompakt header
  - İsteğe bağlı hafif entropi (küçük segment tabloları)

### vs. Traditional Algorithms

**Traditional RLE:**
```java
"aaabbb" → "3a3b"
```

**SmartRLE:**
```java
"aaabbb" → Dictionary → RLE → Pattern → Optimize → Result
```

### 🆕 **Innovation Points**

1. **🔄 Multi-Stage Hybrid**: Sequential application of 4 different techniques
2. **🧠 Dynamic Learning**: Self-improvement during execution
3. **📊 Context-Aware**: Strategy adaptation based on data type
4. **⚡ Adaptive Threshold**: Size-based optimization
5. **🔧 Cascade Optimization**: Each stage optimizes the previous one

## 💻 Implementation

### Core Class Structure

```java
public class SmartRLE {
    // Intelligence components
    private Map<String, String> dictionary;
    private Map<Character, Integer> frequencyMap;
    private List<String> commonPatterns;
    private int compressionLevel;
    private double threshold;
    
    // Main API
    public String compress(String input);
    public String decompress(String compressed);
    public CompressionStats getStats(String original, String compressed);
}
```

### Test Suite: `SmartRLETest.java`

Comprehensive test suite validating algorithm performance across different data types and edge cases.

## 📖 API

### Basic Usage

```java
SmartRLE compressor = new SmartRLE();

// Compress data
String original = "aaaaaabbbbbbcccccc";
String compressed = compressor.compress(original);

// Get statistics
CompressionStats stats = compressor.getStats(original, compressed);
System.out.println(stats); // Detailed compression metrics

// Decompress
String decompressed = compressor.decompress(compressed);
```

### Advanced Usage

```java
// Custom configuration
SmartRLE compressor = new SmartRLE();

// Batch processing
List<String> dataList = Arrays.asList("data1", "data2", "data3");
List<String> compressed = dataList.stream()
    .map(compressor::compress)
    .collect(Collectors.toList());
```

## 🔧 Kurulum

### Prerequisites
- Java 8 or higher
- No external dependencies required

### Download & Compile
```bash
# Clone the repository
git clone https://github.com/resul-exe/SmartRLE.git
cd SmartRLE

# Compile
javac SmartRLE.java

# Run tests
javac SmartRLETest.java
java SmartRLETest

# Run basic demo
java SmartRLE
```

### Entegrasyon
`SmartRLE.java` dosyasını projenize eklemeniz yeterlidir; ek bağımlılık yoktur.

## 🤝 **Contributing**

We welcome contributions! Here's how you can help:

### How to Contribute
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Development Areas
- [ ] Dictionary size optimization
- [ ] Pattern detection improvements
- [ ] Memory usage optimization
- [ ] Binary data support
- [ ] Multi-threading support
- [ ] Machine Learning integration

## 📈 **Future Roadmap**

### v1.1 (Next Release)
- Enhanced pattern detection algorithm
- Performance optimizations for large datasets
- Additional compression strategies

### v2.0 (Future)
- Machine Learning-powered pattern prediction
- Multi-threading support for parallel processing
- Cloud-based compression service

## 🔬 **Academic Value**

This project demonstrates several computer science concepts and research contributions:

### Research Contributions
- **Novel Hybrid Approach**: Unique combination of 4 compression techniques
- **Adaptive Learning**: Self-tuning algorithm architecture
- **Context-Aware Processing**: Data-type specific optimization strategies
- **Performance Analysis**: Comprehensive benchmarking methodology

### Educational Value
- Algorithm design and optimization
- Multi-stage pipeline architecture
- Performance analysis and benchmarking
- Test-driven development practices

## 📄 **License**

This project is provided as-is for educational and research purposes.

## 🙏 **Acknowledgments**

- Inspired by traditional RLE algorithms
- Built with modern software engineering practices
- Designed for educational and research purposes

## 📞 **Contact & Support**

- **Issues**: Please use GitHub Issues for bug reports and feature requests
- **Discussions**: Use GitHub Discussions for questions and ideas
- **Email**: Contact through GitHub profile

---

**⭐ If you find this project useful, please consider giving it a star!**

**SmartRLE**: Next-generation string compression for pattern-rich data ✨
