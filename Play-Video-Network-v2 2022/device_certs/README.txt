شهادات جذر لجهاز أندرويد 4.3 (اختيارية — التطبيق يعمل بدونها لأنه يحمل شهاداته الخاصة)

الملفات
- ISRG_Root_X1.crt / ISRG_Root_X2.crt : للتثبيت من إعدادات الجهاز (صيغة DER).
- <hash>.0 : للتثبيت في نظام الجهاز (يتطلب Root).

الطريقة 1 — من الجهاز (بدون Root)
1. انسخ ملف .crt إلى USB وأوصله بالجهاز.
2. الإعدادات > الأمان > التثبيت من التخزين (أو افتح الملف من مدير الملفات).
3. على أندرويد 4.x يطلب النظام تعيين قفل شاشة (PIN) قبل التثبيت.
   قد تكون القائمة محذوفة في بعض صناديق التلفزيون.

الطريقة 2 — من حاسوب عبر adb (يتطلب Root)
  adb connect IP:5555
  adb shell su -c "mount -o remount,rw /system"
  adb push <hash>.0 /sdcard/
  adb shell su -c "cp /sdcard/<hash>.0 /system/etc/security/cacerts/"
  adb shell su -c "chmod 644 /system/etc/security/cacerts/<hash>.0"
  adb shell su -c "mount -o remount,ro /system"
  adb reboot
(استبدل <hash> باسم الملف الموجود في هذا المجلد.)

ملاحظة: الجهاز الافتراضي (محاكي) لا يغيّر شهادات الصندوق الحقيقي؛ يصلح فقط لتجربة التطبيق قبل التثبيت.
