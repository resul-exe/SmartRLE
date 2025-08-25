# 🚀 SmartRLE — Log Odaklı Kayıpsız Sıkıştırma (Java)

[![Java](https://img.shields.io/badge/Java-8%2B-orange)](https://www.oracle.com/java/)
[![Build Status](https://img.shields.io/badge/Build-Passing-green)](https://github.com/resul-exe/SmartRLE)
[![GitHub stars](https://img.shields.io/github/stars/resul-exe/SmartRLE)](https://github.com/resul-exe/SmartRLE/stargazers)
[![GitHub forks](https://img.shields.io/github/forks/resul-exe/SmartRLE)](https://github.com/resul-exe/SmartRLE/network)
[![Compression](https://img.shields.io/badge/Compression-64.53%25-warning)](README.md)

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
// Sıkıştırıcı örneği oluştur
SmartRLE compressor = new SmartRLE();

// Veriyi sıkıştır
String original = "aaaaaabbbbbbcccccc";
String compressed = compressor.compress(original);

// İstatistik al
SmartRLE.CompressionStats stats = compressor.getStats(original, compressed);
System.out.println(stats); // Orijinal: 18 bayt, Sıkıştırılmış: 9 bayt, Oran: %50.00

// Gerektiğinde açma işlemi
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

### Ana Bileşenler

#### 1) Sözlük (Dictionary)
```java
// Yaygın kelimelerin kısa kodlarla değiştirilmesi
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
// Tekrarlayan kalıpların tespiti ve kodlanması
"abcabc" → "P03" + referans
```

#### 4) Sıklık / Kısa Kodlar (opsiyonel)
```java
// En sık kullanılan karakterler → kısa kodlar
İlk 5 karakter → C0, C1, C2, C3, C4
```

## 🧠 Akıllı Davranışlar

### 🎯 **Öz-Öğrenme Yeteneği**
- Çalışma sırasında veri kalıplarını öğrenir
- Sözlük girişlerini dinamik olarak günceller
- Zaman içinde kalıp tanımayı geliştirir

### ⚡ **Uyarlanabilir Performans**
- Veri boyutuna göre strateji ayarlar
- Küçük veriler için hafif yaklaşım
- Büyük veri kümeleri için agresif sıkıştırma

### 🔍 **Bağlam Farkındalığı**
- Veri tipini otomatik analiz eder
- Optimal sıkıştırma tekniğini seçer
- Çok aşamalı optimizasyon hattı

## 📊 Performans – Log Modu (Apache)

### Gerçek Log Sonucu (apache_access_5mb.log ~ 5.24 MB)

| Araç     | Boyut (bayt) | Oran | Sıkıştırma (ms) | Açma (ms) | Doğruluk |
|----------|---------------|------|------------------|-----------|----------|
| SmartRLE | 3,383,109     | 64.53% | 1527.21 | 534.07 | ✅ |
| GZIP     | 741,640       | 14.15% | 127.30  | –   | – |

Notlar:
- SmartRLE doğruluk odaklıdır; log‑özel pipeline ile EOL/CRLF ve tüm alanlar birebir korunur.
- Token‑LZ (len,dist) katmanı şu an DEVRE DIŞI; güvenli sürüm etkinleştirildiğinde oranların iyileştirilmesi planlanmaktadır.

### ✅ Güçlü Yanlar (Log Modu)
- **%100 Kayıpsız**: decompress(compress(x)) ≡ x garantisi
- **Log-aware**: Apache/Nginx formatını anlayan akıllı normalizasyon
- **EOL Korunumu**: CRLF/LF ve trailing EOL birebir korunur
- **Reversible Header**: Tüm eşlemeler header'da, geri dönüş garantili
- **ASCII-güvenli RLE**: Görünmez karakter sorunu çözüldü
- **Basit API**: Tek sınıf, ek bağımlılık yok

### 🔧 İyileştirme Alanları
- **Sıkıştırma oranı**: GZIP'ten ~4.5x daha büyük çıktı (mevcut)
- **Header şişmesi**: Yüksek çeşitlilikli loglar için büyük metadata
- **Token-LZ devre dışı**: Ana optimizasyon katmanı güvenlik için kapalı
- **Segment eksikliği**: Global header yerine mini-header yaklaşımı gerekli

### 🎯 Uygun Kullanım Senaryoları
- 📄 **Log Dosyaları**: Zaman damgası ve mesaj kalıpları
- ⚙️ **Yapılandırma Dosyaları**: Tekrarlayan ayar yapısı
- 🔄 **Şablon Verileri**: Standart format dosyalar
- 📊 **IoT Verileri**: Kalıplı sensör okumaları

### 🚀 Benchmark Çalıştırma

```bash
javac SmartRLE.java BenchmarkRunner.java
java BenchmarkRunner apache_access_5mb.log
```

Çıktı; orijinal/sonuç boyutları, oran, süreler ve doğruluk kontrolünü içerir.

**Son Test Sonucu** (apache_access_5mb.log):
```
Original size (bytes): 5242918
SmartRLE size (bytes): 3383109 (ratio: 64,53%)
GZIP size (bytes): 741640 (ratio: 14,15%)
SmartRLE compress ms: 1527,21
SmartRLE decompress ms: 534,07
GZIP compress ms: 127,30
Correctness (SmartRLE): true
```

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

### Mevcut Durum (v1.0-log)
- **Odak**: %100 kayıpsızlık ve log-aware özellikler
- **Oran**: %64.53 (GZIP: %14.15) — ~4.5x daha büyük
- **Hız**: 1527ms sıkıştırma, 534ms açma (5MB Apache log)
- **Doğruluk**: ✅ Tam veri bütünlüğü garantisi

### Planlanan İyileştirmeler (v1.1+)
- **Segment mini‑header** (1–4K satır): Global header maliyetini azalt
- **Güvenli Token‑LZ**: len,dist geri başvuru + varint kodlama
- **Header sıkılaştırma**: Path templating, base+delta encoding
- **Guardrail sistemi**: Header/payload oranı kontrolü (%30 hedef)
- **Hedef oran**: %20-30 bandında GZIP ile rekabet

### Geleneksel Algoritmalarla Karşılaştırma

**Geleneksel RLE:**
```java
"aaabbb" → "3a3b"
```

**SmartRLE:**
```java
"aaabbb" → Sözlük → RLE → Kalıp → Optimize → Sonuç
```

### 🆕 **Yenilik Noktaları**

1. **🔄 Çok Aşamalı Hibrit**: 4 farklı tekniğin sıralı uygulanması
2. **🧠 Dinamik Öğrenme**: Çalışma sırasında öz-geliştirme
3. **📊 Bağlam Farkındalığı**: Veri tipine göre strateji uyarlaması
4. **⚡ Uyarlanabilir Eşik**: Boyut tabanlı optimizasyon
5. **🔧 Kademeli Optimizasyon**: Her aşama öncekini optimize eder

## 💻 Uygulama

### Ana Sınıf Yapısı

```java
public class SmartRLE {
    // Zeka bileşenleri
    private Map<String, String> dictionary;
    private Map<Character, Integer> frequencyMap;
    private List<String> commonPatterns;
    private int compressionLevel;
    private double threshold;
    
    // Ana API
    public String compress(String input);
    public String decompress(String compressed);
    public CompressionStats getStats(String original, String compressed);
}
```

### Test Paketi: `BenchmarkRunner.java`

Farklı veri türleri ve uç durumlar için algoritma performansını doğrulayan kapsamlı test paketi.

## 📖 API

### Temel Kullanım

```java
SmartRLE compressor = new SmartRLE();

// Veriyi sıkıştır
String original = "aaaaaabbbbbbcccccc";
String compressed = compressor.compress(original);

// İstatistikleri al
CompressionStats stats = compressor.getStats(original, compressed);
System.out.println(stats); // Detaylı sıkıştırma metrikleri

// Açma işlemi
String decompressed = compressor.decompress(compressed);
```

### Gelişmiş Kullanım

```java
// Özel yapılandırma
SmartRLE compressor = new SmartRLE();

// Toplu işleme
List<String> dataList = Arrays.asList("veri1", "veri2", "veri3");
List<String> compressed = dataList.stream()
    .map(compressor::compress)
    .collect(Collectors.toList());
```

## 🔧 Kurulum

### Gereksinimler
- Java 8 veya üzeri
- Harici bağımlılık gerektirmez

### İndirme ve Derleme
```bash
# Depoyu klonla
git clone https://github.com/resul-exe/SmartRLE.git
cd SmartRLE

# Derle
javac SmartRLE.java

# Testleri çalıştır
javac BenchmarkRunner.java
java BenchmarkRunner apache_access_5mb.log

# Temel demo çalıştır
java SmartRLE
```

### Entegrasyon
`SmartRLE.java` dosyasını projenize eklemeniz yeterlidir; ek bağımlılık yoktur.

## 🤝 **Katkıda Bulunma**

Katkılarınızı memnuniyetle karşılıyoruz! Nasıl yardım edebileceğiniz:

### Katkı Süreci
1. Depoyu fork edin
2. Özellik dalı oluşturun (`git checkout -b feature/harika-ozellik`)
3. Değişikliklerinizi commit edin (`git commit -m 'Harika özellik ekle'`)
4. Dalınıza push edin (`git push origin feature/harika-ozellik`)
5. Pull Request açın

### Geliştirme Alanları
- [ ] Sözlük boyutu optimizasyonu
- [ ] Kalıp tespit iyileştirmeleri
- [ ] Bellek kullanımı optimizasyonu
- [ ] İkili veri desteği
- [ ] Çoklu iş parçacığı desteği
- [ ] Makine Öğrenmesi entegrasyonu

## 📈 **Gelecek Yol Haritası**

### v1.1 (Sonraki Sürüm)
- Gelişmiş kalıp tespit algoritması
- Büyük veri kümeleri için performans optimizasyonları
- Ek sıkıştırma stratejileri

### v2.0 (Gelecek)
- Makine Öğrenmesi destekli kalıp tahmini
- Paralel işleme için çoklu iş parçacığı desteği
- Bulut tabanlı sıkıştırma servisi

## 🔬 **Akademik Değer**

Bu proje birkaç bilgisayar bilimi kavramını ve araştırma katkısını sergiler:

### Araştırma Katkıları
- **Yeni Hibrit Yaklaşım**: 4 sıkıştırma tekniğinin benzersiz kombinasyonu
- **Uyarlanabilir Öğrenme**: Öz-ayarlama algoritma mimarisi
- **Bağlam Farkındalığı**: Veri tipi özel optimizasyon stratejileri
- **Performans Analizi**: Kapsamlı kıyaslama metodolojisi

### Eğitim Değeri
- Algoritma tasarımı ve optimizasyonu
- Çok aşamalı boru hattı mimarisi
- Performans analizi ve kıyaslama
- Test odaklı geliştirme uygulamaları

## 📄 **Lisans**

Bu proje eğitim ve araştırma amaçları için olduğu gibi sağlanmaktadır.

## 🙏 **Teşekkürler**

- Geleneksel RLE algoritmalarından ilham alınmıştır
- Modern yazılım mühendisliği uygulamalarıyla oluşturulmuştur
- Eğitim ve araştırma amaçları için tasarlanmıştır

## 📞 **İletişim ve Destek**

- **Sorunlar**: Hata raporları ve özellik istekleri için GitHub Issues kullanın
- **Tartışmalar**: Sorular ve fikirler için GitHub Discussions kullanın
- **E-posta**: GitHub profili üzerinden iletişim kurun

---

**⭐ Bu projeyi faydalı buluyorsanız, lütfen yıldız vermeyi düşünün!**

**SmartRLE**: Kalıp açısından zengin veriler için yeni nesil string sıkıştırma ✨
