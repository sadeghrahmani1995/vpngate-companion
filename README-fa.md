# دستیار VPN Gate (اندروید) 📱

اپ اندرویدی ساده و سبک برای گرفتن لیست زندهٔ سرورهای **VPN Gate**، فیلتر بر اساس کشور، و بازکردن یک‌ضرب کانفیگ `.ovpn` در اپ **OpenVPN for Android**.

بیلد خودکار با GitHub Actions — آخرین APK از بخش [Releases](https://github.com/sadeghrahmani1995/vpngate-companion/releases/latest) دانلود کنید.

[![Build APK](https://github.com/sadeghrahmani1995/vpngate-companion/actions/workflows/build.yml/badge.svg)](https://github.com/sadeghrahmani1995/vpngate-companion/actions/workflows/build.yml)

---

## نصب

1. فایل `VpngateCompanion.apk` را از [Releases](https://github.com/sadeghrahmani1995/vpngate-companion/releases/latest) دانلود و نصب کنید (اجازهٔ «نصب از منابع ناشناس» را بدهید).
2. اپ **OpenVPN for Android** (سازندهٔ Arne Schwabe — پکیج `de.blinkt.openvpn`) را نصب کنید:
   - گوگل‌پلی: جستجوی «OpenVPN for Android»
   - اگر پلی در دسترس نیست: سایت رسمی `https://ics-openvpn.blinkt.de` یا ریپوی گیت‌هاب `schwabe/ics-openvpn`
3. اپ «دستیار VPN Gate» را باز کنید و روی **«دریافت لیست سرورها»** بزنید.
4. کشور را از منوی بالا فیلتر کنید و روی سرور دلخواه بزنید — کانفیگ مستقیماً در OpenVPN for Android باز می‌شود؛ فقط «Add / OK» را بزنید و وصل شوید (نام کاربری و رمز: `vpn` / `vpn` — معمولاً خودکار است).

## امکانات

- دریافت لیست زنده با زنجیرهٔ ۴ منبعی (رسمی vpngate.net → آینهٔ GitHub → jsDelivr) با ریدایرکت‌ترکر خودکار
- اگر همهٔ منابع رسمی در شبکهٔ شما فیلتر باشد: آدرس هر منبع CSV جایگزین را در کادر بالا بگذارید (مثلاً خروجی `cache.csv` تستر ویندوزی خودتان را روی یک لینک بگذارید)
- فیلتر کشور + مرتب‌سازی بر اساس Score + نمایش سرعت/پینگ/تعداد کاربر
- **دکمهٔ «بازکردن فایل .ovpn»**: فایل‌های سالم تست‌شده روی کامپیوتر (پوشهٔ `vpngate-data\good`) را به گوشی منتقل کنید و همین‌جا باز کنید
- فارسی و راست‌به‌چپ، بدون هیچ دسترسی اضافه (فقط INTERNET)

## نکته برای ایران

- سرورهای VPN Gate عمومی و ناپایدارند و ممکن است خیلی از آن‌ها از داخل ایران پاسخ ندهند — برای انتخاب سرورِ سالم‌تر، اول روی کامپیوتر با **vpngate-pro** تست بگیرید و فایل‌های `good` را با گوشی باز کنید.
- این اپ «موتور VPN» نیست؛ کانفیگ را به اپ OpenVPN می‌سپارد. موتور اتصال همان OpenVPN for Android است که پایدارترین راه رسمی است.

## بیلد محلی (اختیاری)

```bash
gradle assembleDebug          # خروجی: app/build/outputs/apk/debug/app-debug.apk
```

نیازمندی: JDK 17، اندروید SDK (پلتفرم 34).

## سلب مسئولیت

سرورهای VPN Gate توسط داوطلبان در سراسر جهان ارائه می‌شوند و ترافیک آن‌ها رمزنگاری‌شده اما ناشناس نیست — برای کارهای حساس استفاده نکنید. این پروژه صرفاً ابزار دسترسی به لیست عمومی است.
