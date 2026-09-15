package com.zltm90plus.app.data.remote

/**
 * Every user-visible value in the app is supplied in Arabic at the UI layer, but the API
 * layer must describe failures precisely enough for the "technical details" panel without
 * ever including credentials.
 */
sealed class RouterError(
    /** Short Arabic message shown to the user. */
    val userMessage: String,
    /** Optional detail for the developer panel. Never contains secrets. */
    val technicalDetail: String? = null,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {

    class PhoneNotConnectedToDeviceNetwork :
        RouterError("الهاتف غير متصل بشبكة Wi-Fi الخاصة بالجهاز. اتصل بشبكة ZLT ثم أعد المحاولة.")

    class DeviceNotFound(detail: String? = null) :
        RouterError("لم يتم العثور على الجهاز على هذا العنوان. تحقق من العنوان ومن أن الجهاز قيد التشغيل.", detail)

    class InvalidCredentials :
        RouterError("اسم المستخدم أو كلمة المرور غير صحيحة.")

    class SessionExpired :
        RouterError("انتهت الجلسة. يرجى تسجيل الدخول مرة أخرى.")

    class Timeout(detail: String? = null) :
        RouterError("لم يستجب الجهاز في الوقت المتوقع. حاول مرة أخرى.", detail)

    class FeatureNotSupported(feature: String, detail: String? = null) :
        RouterError("هذه الميزة غير متاحة من الجهاز ($feature).", detail)

    class UnsupportedFirmware(detail: String? = null) :
        RouterError("واجهة الجهاز غير مدعومة في هذا الإصدار من Firmware.", detail)

    class TemporaryFailure(detail: String? = null, cause: Throwable? = null) :
        RouterError("حدث خطأ مؤقت. يمكنك إعادة المحاولة.", detail, cause)
}
