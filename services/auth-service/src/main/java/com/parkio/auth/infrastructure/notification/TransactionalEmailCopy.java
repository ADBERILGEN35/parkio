package com.parkio.auth.infrastructure.notification;

import com.parkio.auth.domain.EmailLocale;

/**
 * Localized subject and body fragments for verification and password-reset emails.
 * Fragments do not include the action link; callers fill that in.
 */
final class TransactionalEmailCopy {

    private TransactionalEmailCopy() {
    }

    record Copy(
            String subject,
            String heading,
            String body,
            String cta,
            String fallbackLinkIntro,
            String ignoreNote) {
    }

    static Copy verification(EmailLocale locale) {
        if (locale == EmailLocale.EN) {
            return new Copy(
                    "Verify your Parkio email",
                    "Verify your Parkio email",
                    "Welcome to Parkio. Tap the button below to verify your email address. This link expires soon for your security.",
                    "Verify email",
                    "If the button does not work, copy and paste this link into your browser:",
                    "If you did not create a Parkio account, you can ignore this email.");
        }
        return new Copy(
                "Parkio e-posta adresinizi doğrulayın",
                "Parkio e-posta adresinizi doğrulayın",
                "Parkio'ya hoş geldiniz. E-posta adresinizi doğrulamak için aşağıdaki düğmeye dokunun. Güvenliğiniz için bu bağlantının süresi yakında dolacak.",
                "E-posta adresini doğrula",
                "Düğme çalışmazsa bu bağlantıyı kopyalayıp tarayıcınıza yapıştırın:",
                "Parkio hesabı oluşturmadıysanız bu e-postayı yok sayabilirsiniz.");
    }

    static Copy passwordReset(EmailLocale locale) {
        if (locale == EmailLocale.EN) {
            return new Copy(
                    "Reset your Parkio password",
                    "Reset your Parkio password",
                    "We received a request to reset your password. Tap the button below to choose a new one. This link expires soon for your security.",
                    "Reset password",
                    "If the button does not work, copy and paste this link into your browser:",
                    "If you did not request a password reset, you can ignore this email.");
        }
        return new Copy(
                "Parkio şifrenizi sıfırlayın",
                "Parkio şifrenizi sıfırlayın",
                "Parkio şifrenizi sıfırlamak için bir istek aldık. Yeni bir şifre seçmek için aşağıdaki düğmeye dokunun. Güvenliğiniz için bu bağlantının süresi yakında dolacak.",
                "Şifreyi sıfırla",
                "Düğme çalışmazsa bu bağlantıyı kopyalayıp tarayıcınıza yapıştırın:",
                "Şifre sıfırlama talebinde bulunmadıysanız bu e-postayı yok sayabilirsiniz.");
    }
}