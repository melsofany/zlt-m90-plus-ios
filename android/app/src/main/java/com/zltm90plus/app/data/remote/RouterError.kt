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

    /**
     * The device's own files name an interface this build does not implement.
     *
     * Kept apart from [InvalidCredentials] and [UnsupportedFirmware], because the credentials were
     * never evaluated. The M90 Plus 1.12.8 field log showed a login posted to `/cgi-bin/http.cgi`,
     * an endpoint named by the device's own bundle, answered `{"success":false,"cmd":-1,
     * "message":"ROOT IS NULL."}` — a dispatcher that has no `goformId` concept at all. Nothing
     * there judged the password, yet the session check that followed 404'd and turned that into
     * "wrong password", which sent the user looking for a credential problem that did not exist.
     */
    class InterfaceNotSupported(val endpoint: String, val deviceSaid: String? = null) :
        RouterError(
            "واجهة الجهاز على $endpoint غير مدعومة في هذا الإصدار، ولم تُقيَّم كلمة المرور.",
            technicalDetail = listOfNotNull(
                "endpoint=$endpoint",
                deviceSaid?.takeIf { it.isNotBlank() }?.let { "device=$it" },
            ).joinToString(" "),
        )

    /**
     * The device refused a login, and this build cannot tell whether the password or the digest
     * recipe was at fault.
     *
     * The `http.cgi` interface never receives the password — only a 64-character digest of it — and
     * which inputs feed that digest is not yet confirmed. So a refusal has two possible causes, and
     * the honest thing is to name both rather than pick one. Reporting it as a wrong password would
     * repeat exactly the mistake `1d4422f` fixed: accusing a credential nothing had evaluated.
     */
    class LoginRejected(
        val deviceSaid: String? = null,
        val digestScheme: String? = null,
    ) : RouterError(
        "رفض الجهاز تسجيل الدخول. تحقق من اسم المستخدم وكلمة المرور، وإن كانتا صحيحتين فطريقة " +
            "اشتقاق كلمة المرور غير مطابقة لهذا الإصدار.",
        technicalDetail = listOfNotNull(
            deviceSaid?.takeIf { it.isNotBlank() }?.let { "device=$it" },
            digestScheme?.takeIf { it.isNotBlank() }?.let { "digest=$it" },
        ).joinToString(" "),
    )

    /**
     * The device answered, but not with HTTP this client can use.
     *
     * Kept apart from [TemporaryFailure] on purpose. Retrying cannot help — the same request will
     * produce the same answer — and telling the user "حدث خطأ مؤقت، أعد المحاولة" is what turned a
     * device that was clearly replying into what looked like a network fault.
     */
    class DeviceResponseUnreadable(detail: String? = null, val rawReply: String? = null) :
        RouterError(
            "ردّ الجهاز باستجابة غير مفهومة، ولم يُكمل التطبيق الطلب. السجل أدناه يوضح ما ردّ به الجهاز.",
            detail,
        )

    /**
     * The device refused the request and named the address that does serve it.
     *
     * The follow-up request is built by the API layer, which owns the route paths; this only
     * carries the target so the reason stays legible in the connection log.
     */
    class Redirected(val scheme: String, val authority: String, detail: String? = null) :
        RouterError("الجهاز يحوّل الطلب إلى عنوان آخر. أعاد التطبيق المحاولة على العنوان الجديد.", detail)

    /**
     * The phone is on a Wi-Fi network, but not the one the device is on.
     *
     * This is the failure the first field log actually showed: every request timed out, and the
     * app reported "الجهاز لم يستجب" when the request had never had a route to the device at all.
     * Naming the two addresses is what makes it actionable.
     */
    class WrongNetwork(val deviceHost: String, val phoneAddress: String?) :
        RouterError(
            "هاتفك متصل بشبكة Wi-Fi مختلفة عن شبكة الجهاز. " +
                "عنوان الجهاز $deviceHost بينما هاتفك على ${phoneAddress ?: "شبكة أخرى"}، " +
                "وهما شبكتان مختلفتان. اتصل بشبكة الجهاز نفسه ثم أعد المحاولة.",
            technicalDetail = "device=$deviceHost phone=$phoneAddress",
        )

    class SessionExpired :
        RouterError("انتهت الجلسة. يرجى تسجيل الدخول مرة أخرى.")

    class Timeout(detail: String? = null) :
        RouterError("لم يستجب الجهاز في الوقت المتوقع. حاول مرة أخرى.", detail)

    class FeatureNotSupported(feature: String, detail: String? = null) :
        RouterError("هذه الميزة غير متاحة من الجهاز ($feature).", detail)

    class UnsupportedFirmware(detail: String? = null) :
        RouterError("واجهة الجهاز غير مدعومة في هذا الإصدار من Firmware.", detail)

    /**
     * The typed address is not a LAN address, is malformed, or actually answered with a redirect
     * to somewhere else. Kept separate from [DeviceNotFound] because the fix is different: the
     * user has to correct the address rather than check the cable or the power.
     */
    class InvalidHost(detail: String? = null) :
        RouterError(
            "عنوان الجهاز يجب أن يكون عنوانًا محليًا (مثل 192.168.1.1) على شبكة Wi-Fi الخاصة بالجهاز.",
            detail,
        )

    class TemporaryFailure(detail: String? = null, cause: Throwable? = null) :
        RouterError("حدث خطأ مؤقت. يمكنك إعادة المحاولة.", detail, cause)
}
