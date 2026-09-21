package com.parkio.gateway.application.waitlist;

public interface WaitlistEmailSender {

    void sendConfirmation(String email, String verificationToken, String withdrawToken, String locale);

    void sendWithdrawalNotice(String email, String locale);
}
