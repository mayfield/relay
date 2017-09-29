package io.forsta.securesms.sms;

import android.content.Context;

import io.forsta.ccsm.api.model.ForstaMessage;
import io.forsta.ccsm.database.model.ForstaThread;
import io.forsta.ccsm.util.ForstaUtils;
import io.forsta.securesms.database.model.SmsMessageRecord;
import io.forsta.securesms.recipients.Recipients;

public class OutgoingTextMessage {

  private final Recipients recipients;
  protected String     message;
  private final int        subscriptionId;
  private final long       expiresIn;

  public OutgoingTextMessage(Recipients recipients, String message, int subscriptionId) {
    this(recipients, message, 0, subscriptionId);
  }

  public OutgoingTextMessage(Recipients recipients, String message, long expiresIn, int subscriptionId) {
    this.recipients     = recipients;
    this.message        = message;
    this.expiresIn      = expiresIn;
    this.subscriptionId = subscriptionId;
  }

  protected OutgoingTextMessage(OutgoingTextMessage base, String body) {
    this.recipients     = base.getRecipients();
    this.subscriptionId = base.getSubscriptionId();
    this.expiresIn      = base.getExpiresIn();
    this.message        = body;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public String getMessageBody() {
    return message;
  }

  public Recipients getRecipients() {
    return recipients;
  }

  public boolean isKeyExchange() {
    return false;
  }

  public boolean isSecureMessage() {
    return false;
  }

  public boolean isEndSession() {
    return false;
  }

  public boolean isPreKeyBundle() {
    return false;
  }

  public static OutgoingTextMessage from(SmsMessageRecord record) {
    if (record.isSecure()) {
      return new OutgoingEncryptedMessage(record.getRecipients(), record.getBody().getBody(), record.getExpiresIn());
    } else if (record.isKeyExchange()) {
      return new OutgoingKeyExchangeMessage(record.getRecipients(), record.getBody().getBody());
    } else if (record.isEndSession()) {
      return new OutgoingEndSessionMessage(new OutgoingTextMessage(record.getRecipients(), record.getBody().getBody(), 0, -1));
    } else {
      return new OutgoingTextMessage(record.getRecipients(), record.getBody().getBody(), record.getExpiresIn(), record.getSubscriptionId());
    }
  }

  public OutgoingTextMessage withBody(String body) {
    return new OutgoingTextMessage(this, body);
  }

  public void setForstaJsonBody(Context context, ForstaThread forstaThread) {
    this.message = ForstaMessage.createForstaMessageBody(context, this.message, recipients, forstaThread);
  }

}
