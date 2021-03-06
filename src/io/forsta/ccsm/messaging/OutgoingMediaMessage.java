package io.forsta.ccsm.messaging;

import android.text.TextUtils;

import io.forsta.securesms.attachments.Attachment;
import io.forsta.securesms.mms.SlideDeck;
import io.forsta.securesms.recipients.Recipients;

import java.util.List;

public class OutgoingMediaMessage {

  private   final Recipients       recipients;
  protected String                 body;
  protected final List<Attachment> attachments;
  private   final long             sentTimeMillis;
  private   final int              distributionType;
  private   final int              subscriptionId;
  private   final long             expiresIn;
  private final String messageRef;
  private final int voteCount;
  private final String messageId;

  public OutgoingMediaMessage(Recipients recipients, String message,
                              List<Attachment> attachments, long sentTimeMillis,
                              int subscriptionId, long expiresIn,
                              int distributionType)
  {
    this.recipients       = recipients;
    this.body             = message;
    this.sentTimeMillis   = sentTimeMillis;
    this.distributionType = distributionType;
    this.attachments      = attachments;
    this.subscriptionId   = subscriptionId;
    this.expiresIn        = expiresIn;
    this.messageRef = null;
    this.voteCount = 0;
    this.messageId = null;
  }

  public OutgoingMediaMessage(Recipients recipients, String message,
                              List<Attachment> attachments, long sentTimeMillis,
                              int subscriptionId, long expiresIn,
                              int distributionType, String messageId, String messageRef, int voteCount)
  {
    this.recipients       = recipients;
    this.body             = message;
    this.sentTimeMillis   = sentTimeMillis;
    this.distributionType = distributionType;
    this.attachments      = attachments;
    this.subscriptionId   = subscriptionId;
    this.expiresIn        = expiresIn;
    this.messageRef = messageRef;
    this.voteCount = voteCount;
    this.messageId = messageId;
  }

  public OutgoingMediaMessage(OutgoingMediaMessage that) {
    this.recipients       = that.getRecipients();
    this.body             = that.body;
    this.distributionType = that.distributionType;
    this.attachments      = that.attachments;
    this.sentTimeMillis   = that.sentTimeMillis;
    this.subscriptionId   = that.subscriptionId;
    this.expiresIn        = that.expiresIn;
    this.messageRef = that.messageRef;
    this.voteCount = that.voteCount;
    this.messageId = that.messageId;
  }

  public Recipients getRecipients() {
    return recipients;
  }

  public String getBody() {
    return body;
  }

  public List<Attachment> getAttachments() {
    return attachments;
  }

  public int getDistributionType() {
    return distributionType;
  }

  public boolean isSecure() {
    return true;
  }

  public boolean isEndSession() {
    return false;
  }

  public boolean isKeyExchange() {
    return false;
  }

  public boolean isGroup() {
    return false;
  }

  public boolean isExpirationUpdate() {
    return false;
  }

  public long getSentTimeMillis() {
    return sentTimeMillis;
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public String getMessageRef() {
    return messageRef;
  }

  public String getMessageId() {
    return messageId;
  }

  public int getVoteCount() {
    return voteCount;
  }

  private static String buildMessage(SlideDeck slideDeck, String message) {
    if (!TextUtils.isEmpty(message) && !TextUtils.isEmpty(slideDeck.getBody())) {
      return slideDeck.getBody() + "\n\n" + message;
    } else if (!TextUtils.isEmpty(message)) {
      return message;
    } else {
      return slideDeck.getBody();
    }
  }
}
