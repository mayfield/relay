package io.forsta.securesms.jobs;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.work.WorkerParameters;
import io.forsta.ccsm.api.CcsmApi;
import io.forsta.ccsm.api.model.ForstaDistribution;
import io.forsta.ccsm.api.model.ForstaMessage;
import io.forsta.ccsm.messaging.ForstaMessageManager;
import io.forsta.ccsm.messaging.OutgoingMessage;
import io.forsta.ccsm.service.ForstaServiceAccountManager;
import io.forsta.ccsm.util.InvalidMessagePayloadException;
import io.forsta.securesms.ApplicationContext;
import io.forsta.securesms.BuildConfig;
import io.forsta.securesms.attachments.DatabaseAttachment;
import io.forsta.securesms.attachments.PointerAttachment;
import io.forsta.securesms.crypto.IdentityKeyUtil;
import io.forsta.securesms.crypto.MasterSecret;
import io.forsta.securesms.crypto.MasterSecretUnion;
import io.forsta.securesms.crypto.MasterSecretUtil;
import io.forsta.securesms.crypto.SecurityEvent;
import io.forsta.securesms.crypto.storage.SignalProtocolStoreImpl;
import io.forsta.securesms.crypto.storage.TextSecureSessionStore;
import io.forsta.securesms.database.DatabaseFactory;
import io.forsta.securesms.database.MessagingDatabase.SyncMessageId;
import io.forsta.securesms.database.MmsDatabase;
import io.forsta.securesms.database.NoSuchMessageException;
import io.forsta.securesms.database.PushDatabase;
import io.forsta.securesms.jobmanager.JobParameters;
import io.forsta.securesms.jobmanager.SafeData;
import io.forsta.ccsm.messaging.IncomingMediaMessage;
import io.forsta.ccsm.messaging.OutgoingExpirationUpdateMessage;
import io.forsta.ccsm.messaging.OutgoingMediaMessage;
import io.forsta.securesms.notifications.MessageNotifier;
import io.forsta.securesms.push.TextSecureCommunicationFactory;
import io.forsta.securesms.recipients.RecipientFactory;
import io.forsta.securesms.recipients.Recipients;
import io.forsta.securesms.service.KeyCachingService;
import io.forsta.securesms.service.WebRtcCallService;
import io.forsta.securesms.util.Base64;
import io.forsta.securesms.util.DirectoryHelper;
import io.forsta.securesms.util.TextSecurePreferences;

import org.webrtc.IceCandidate;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.state.SessionStore;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage;
import org.whispersystems.signalservice.api.messages.multidevice.RequestMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.util.ArrayList;
import java.util.List;
import ws.com.google.android.mms.MmsException;
import androidx.work.Data;

public class PushDecryptJob extends ContextJob {

  private static final long serialVersionUID = 2L;

  public static final String TAG = PushDecryptJob.class.getSimpleName();
  private static final String KEY_MESSAGE_ID = "message_id";
  private static final String KEY_SMS_MESSAGE_ID = "sms_message_id";

  private long messageId;
  private long smsMessageId;

  public PushDecryptJob(@NonNull Context context, @NonNull WorkerParameters workerParameters) {
    super(context, workerParameters);
  }

  public PushDecryptJob(Context context, long pushMessageId, String sender) {
    this(context, pushMessageId, -1, sender);
  }

  public PushDecryptJob(Context context, long pushMessageId, long smsMessageId, String sender) {
    super(context, JobParameters.newBuilder()
                                .withGroupId("__PUSH_DECRYPT_JOB__")
                                .create());
    this.messageId    = pushMessageId;
    this.smsMessageId = smsMessageId;
  }

  @Override
  public void onAdded() {}

  protected void initialize(@NonNull SafeData data) {
    messageId    = data.getLong(KEY_MESSAGE_ID);
    smsMessageId = data.getLong(KEY_SMS_MESSAGE_ID);
  }

  @Override
  protected @NonNull Data serialize(@NonNull Data.Builder dataBuilder) {
    return dataBuilder.putLong(KEY_MESSAGE_ID, messageId)
        .putLong(KEY_SMS_MESSAGE_ID, smsMessageId)
        .build();
  }

  @Override
  public void onRun() throws NoSuchMessageException {
    synchronized (PushReceivedJob.RECEIVE_LOCK) {
      if (!IdentityKeyUtil.hasIdentityKey(context)) {
        Log.w(TAG, "Skipping job, waiting for migration...");
        MessageNotifier.updateNotification(context, null, true, -2);
        return;
      }

      PushDatabase          database             = DatabaseFactory.getPushDatabase(context);
      SignalServiceEnvelope envelope             = database.get(messageId);
      Optional<Long>        optionalSmsMessageId = smsMessageId > 0 ? Optional.of(smsMessageId) :
                                                                   Optional.<Long>absent();

      try {
        MasterSecretUnion masterSecretUnion;
        MasterSecret masterSecret = KeyCachingService.getMasterSecret(context);
        if (masterSecret == null) masterSecretUnion = new MasterSecretUnion(MasterSecretUtil.getAsymmetricMasterSecret(context, null));
        else                      masterSecretUnion = new MasterSecretUnion(masterSecret);

        handleMessage(masterSecretUnion, envelope, optionalSmsMessageId);
        database.delete(messageId);
      } catch (Exception e) {
        Log.e(TAG, "Exception: " + e.getMessage());
        e.printStackTrace();
      }
    }
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() {

  }

  private void handleMessage(MasterSecretUnion masterSecret, SignalServiceEnvelope envelope, Optional<Long> smsMessageId) {
    synchronized (PushReceivedJob.RECEIVE_LOCK) {
      try {
        SignalProtocolStore  axolotlStore = new SignalProtocolStoreImpl(context);
        SignalServiceAddress localAddress = new SignalServiceAddress(TextSecurePreferences.getLocalNumber(context));
        SignalServiceCipher  cipher       = new SignalServiceCipher(localAddress, axolotlStore);

        SignalServiceContent content = null;
        try {
          content = cipher.decrypt(envelope);
        } catch (UntrustedIdentityException e) {
          SignalServiceCipher updatedCypher = autoHandleUntrustedIdentity(envelope, localAddress, axolotlStore);
          content = updatedCypher.decrypt(envelope);
        }

        if (content.getDataMessage().isPresent()) {
          SignalServiceDataMessage message = content.getDataMessage().get();

          if (message.isEndSession())                    handleEndSessionMessage(masterSecret, envelope, message);
          else if (message.isExpirationUpdate())         handleExpirationUpdate(masterSecret, envelope, message, smsMessageId);
          else                                           handleMediaMessage(masterSecret, envelope, message, smsMessageId);
        } else if (content.getSyncMessage().isPresent()) {
          SignalServiceSyncMessage syncMessage = content.getSyncMessage().get();

          if      (syncMessage.getSent().isPresent())    handleSynchronizeSentMessage(masterSecret, envelope, syncMessage.getSent().get(), smsMessageId);
          else if (syncMessage.getRequest().isPresent()) handleSynchronizeRequestMessage(masterSecret, syncMessage.getRequest().get());
          else if (syncMessage.getRead().isPresent())    handleSynchronizeReadMessage(masterSecret, syncMessage.getRead().get(), envelope.getTimestamp());
          else                                           Log.w(TAG, "Contains no known sync types...");
        }

        if (envelope.isPreKeySignalMessage()) {
          ApplicationContext.getInstance(context).getJobManager().add(new RefreshPreKeysJob(context));
        }
      } catch (InvalidVersionException e) {
        Log.w(TAG, e);
//      handleInvalidVersionMessage(masterSecret, envelope, smsMessageId);
      } catch (InvalidMessageException | InvalidKeyIdException | InvalidKeyException | MmsException e) {
        Log.w(TAG, e);
//      handleCorruptMessage(masterSecret, envelope, smsMessageId);
      } catch (NoSessionException e) {
        Log.w(TAG, e);
//      handleNoSessionMessage(masterSecret, envelope, smsMessageId);
      } catch (LegacyMessageException e) {
        Log.w(TAG, e);
//      handleLegacyMessage(masterSecret, envelope, smsMessageId);
      } catch (DuplicateMessageException e) {
        Log.w(TAG, e);
//      handleDuplicateMessage(masterSecret, envelope, smsMessageId);
      } catch (UntrustedIdentityException e) {
        Log.w(TAG, e);
//      handleUntrustedIdentityMessage(masterSecret, envelope, smsMessageId);
      } catch (InvalidMessagePayloadException e) {
        Log.e(TAG, "Invalid Forsta message body");
        e.printStackTrace();
      }
    }
  }

  private SignalServiceCipher autoHandleUntrustedIdentity(SignalServiceEnvelope envelope, SignalServiceAddress localAddress, SignalProtocolStore axolotlStore) throws InvalidVersionException, InvalidMessageException {
    Log.w(TAG, "Auto handling untrusted identity");
    Recipients recipients = RecipientFactory.getRecipientsFromString(context, envelope.getSource(), false);
    long recipientId = recipients.getPrimaryRecipient().getRecipientId();
    Log.w(TAG, "From recipient: " + recipients.getPrimaryRecipient().getAddress() + " " + recipients.getPrimaryRecipient().getName());
    byte[] encryptedContent = (!envelope.hasLegacyMessage() && envelope.hasContent()) ? envelope.getContent() : envelope.getLegacyMessage();
    PreKeySignalMessage whisperMessage = new PreKeySignalMessage(encryptedContent);
    IdentityKey identityKey = whisperMessage.getIdentityKey();
    DatabaseFactory.getIdentityDatabase(context).saveIdentity(recipientId, identityKey);
    return new SignalServiceCipher(localAddress, axolotlStore);
  }

  private void handleExpirationUpdate(@NonNull MasterSecretUnion masterSecret,
                                      @NonNull SignalServiceEnvelope envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @NonNull Optional<Long> smsMessageId)
      throws MmsException, InvalidMessagePayloadException {
    MmsDatabase          database     = DatabaseFactory.getMmsDatabase(context);
    String               localNumber  = TextSecurePreferences.getLocalNumber(context);
    String                body       = message.getBody().isPresent() ? message.getBody().get() : "";
    IncomingMediaMessage mediaMessage = new IncomingMediaMessage(masterSecret, envelope.getSource(),
                                                                 localNumber, message.getTimestamp(), -1,
                                                                 message.getExpiresInSeconds() * 1000, true,
                                                                 Optional.fromNullable(envelope.getRelay()),
                                                                 message.getBody(), message.getGroupInfo(),
                                                                 Optional.<List<SignalServiceAttachment>>absent());

    ForstaMessage forstaMessage = ForstaMessageManager.fromMessagBodyString(body);
    long threadId = updateThreadDistribution(forstaMessage, masterSecret.getMasterSecret().get());

    database.insertSecureDecryptedMessageInbox(masterSecret, mediaMessage, threadId);
    DatabaseFactory.getThreadPreferenceDatabase(context).setExpireMessages(threadId, message.getExpiresInSeconds());
  }

  private void handleEndSessionMessage(@NonNull MasterSecretUnion masterSecret,
                                       @NonNull SignalServiceEnvelope    envelope,
                                       @NonNull SignalServiceDataMessage message)
  {
    SignalProtocolAddress addr = new SignalProtocolAddress(envelope.getSource(), envelope.getSourceDevice());
    Log.w(TAG, "Deleting session for: " + addr);
    SessionStore sessionStore = new TextSecureSessionStore(context);
    // See if we can just archive the current session, instead of deleting it.
    sessionStore.deleteSession(addr);
    SecurityEvent.broadcastSecurityUpdateEvent(context);
  }

  private long handleSynchronizeSentEndSessionMessage(@NonNull SentTranscriptMessage message)
  {
    String destination = message.getDestination().get();
    if (destination == null) {
      Log.e(TAG, "Invalid recipeint for end session");
    } else {
      Log.w(TAG, "Deleting sessions for: " + destination);
      SessionStore sessionStore = new TextSecureSessionStore(context);
      sessionStore.deleteAllSessions(destination);
      SecurityEvent.broadcastSecurityUpdateEvent(context);
    }
    return -1;
  }

  private void handleSynchronizeSentMessage(@NonNull MasterSecretUnion masterSecret,
                                            @NonNull SignalServiceEnvelope envelope,
                                            @NonNull SentTranscriptMessage message,
                                            @NonNull Optional<Long> smsMessageId)
      throws MmsException, InvalidMessagePayloadException {
    Long threadId;

    if (message.getMessage().isEndSession()) {
      Log.e(TAG, "Sync end session is invalid: Only send directly to peers");
      //threadId = handleSynchronizeSentEndSessionMessage(message);
      threadId = -1L;
    } else if (message.getMessage().isExpirationUpdate()) {
      threadId = handleSynchronizeSentExpirationUpdate(masterSecret, message, smsMessageId);
    } else {
      threadId = handleSynchronizeSentMediaMessage(masterSecret, envelope, message, smsMessageId);
    }

    if (threadId != -1) {
      DatabaseFactory.getThreadDatabase(getContext()).setRead(threadId);
      MessageNotifier.updateNotification(getContext(), masterSecret.getMasterSecret().orNull());
    }
  }

  private void handleSynchronizeRequestMessage(@NonNull MasterSecretUnion masterSecret,
                                               @NonNull RequestMessage message)
  {
    if (message.isContactsRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceContactUpdateJob(getContext()));
    }

    if (message.isBlockedListRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
                        .add(new MultiDeviceBlockedUpdateJob(getContext()));
    }
  }

  private void handleSynchronizeReadMessage(@NonNull MasterSecretUnion masterSecret,
                                            @NonNull List<ReadMessage> readMessages,
                                            long envelopeTimestamp)
  {
    for (ReadMessage readMessage : readMessages) {
      List<Pair<Long, Long>> expiringMedia = DatabaseFactory.getMmsDatabase(context).setTimestampRead(new SyncMessageId(readMessage.getSender(), readMessage.getTimestamp()), envelopeTimestamp);

      for (Pair<Long, Long> expiringMessage : expiringMedia) {
        ApplicationContext.getInstance(context)
                          .getExpiringMessageManager()
                          .scheduleDeletion(expiringMessage.first, true, envelopeTimestamp, expiringMessage.second);
      }
    }

    MessageNotifier.updateNotification(context, masterSecret.getMasterSecret().orNull());
  }

  private void handleMediaMessage(@NonNull MasterSecretUnion masterSecret,
                                  @NonNull SignalServiceEnvelope envelope,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId)
      throws MmsException, InvalidMessagePayloadException {
    String                body       = message.getBody().isPresent() ? message.getBody().get() : "";
    ForstaMessage forstaMessage = ForstaMessageManager.fromMessagBodyString(body);
    forstaMessage.setSenderId(envelope.getSource());
    forstaMessage.setDeviceId(envelope.getSourceDevice());
    forstaMessage.setTimeStamp(envelope.getTimestamp());

    if (forstaMessage.getMessageType().equals(ForstaMessage.MessageTypes.CONTENT)) {
      handleContentMessage(forstaMessage, masterSecret, message, envelope);
    } else {
      handleControlMessage(forstaMessage, masterSecret.getMasterSecret().get());
    }
  }

  private long handleSynchronizeSentExpirationUpdate(@NonNull MasterSecretUnion masterSecret,
                                                     @NonNull SentTranscriptMessage message,
                                                     @NonNull Optional<Long> smsMessageId)
      throws MmsException, InvalidMessagePayloadException {
    MmsDatabase database   = DatabaseFactory.getMmsDatabase(context);

    ForstaMessage forstaMessage = ForstaMessageManager.fromMessagBodyString(message.getMessage().getBody().get());
    ForstaDistribution distribution = CcsmApi.getMessageDistribution(context, forstaMessage.getUniversalExpression());
    Recipients recipients = getDistributionRecipients(distribution);
    long threadId = DatabaseFactory.getThreadDatabase(context).getOrAllocateThreadId(recipients, forstaMessage, distribution);

    OutgoingExpirationUpdateMessage expirationUpdateMessage = new OutgoingExpirationUpdateMessage(recipients,
        message.getTimestamp(),
        message.getMessage().getExpiresInSeconds() * 1000);

    long messageId = database.insertMessageOutbox(masterSecret, expirationUpdateMessage, threadId, false);

    database.markAsSent(messageId);
    database.markAsPush(messageId);

    DatabaseFactory.getThreadPreferenceDatabase(context).setExpireMessages(threadId, message.getMessage().getExpiresInSeconds());

    return threadId;
  }

  private long handleSynchronizeSentMediaMessage(@NonNull MasterSecretUnion masterSecret,
                                                 @NonNull SignalServiceEnvelope envelope,
                                                 @NonNull SentTranscriptMessage message,
                                                 @NonNull Optional<Long> smsMessageId)
      throws MmsException, InvalidMessagePayloadException {
    MmsDatabase           database     = DatabaseFactory.getMmsDatabase(context);

    ForstaMessage forstaMessage = ForstaMessageManager.fromMessagBodyString(message.getMessage().getBody().get());
    forstaMessage.setTimeStamp(message.getTimestamp());
    forstaMessage.setSenderId(envelope.getSource());
    forstaMessage.setDeviceId(envelope.getSourceDevice());
    forstaMessage.setTimeStamp(envelope.getTimestamp());
    if (forstaMessage.getMessageType().equals(ForstaMessage.MessageTypes.CONTENT)) {
      ForstaDistribution distribution = CcsmApi.getMessageDistribution(context, forstaMessage.getUniversalExpression());
      Recipients recipients = getDistributionRecipients(distribution);
      DirectoryHelper.refreshDirectoryFor(context, masterSecret.getMasterSecret().get(), recipients);
      recipients.setStale();
      recipients = RecipientFactory.getRecipientsFor(context, recipients.getRecipientsList(), false);
      long threadId = updateThreadDistribution(forstaMessage, masterSecret.getMasterSecret().get());

      if (DatabaseFactory.getThreadPreferenceDatabase(context).getExpireMessages(threadId) != message.getMessage().getExpiresInSeconds()) {
        handleSynchronizeSentExpirationUpdate(masterSecret, message, Optional.<Long>absent());
      }

      OutgoingMediaMessage  mediaMessage = new OutgoingMessage(recipients, message.getMessage().getBody().orNull(),
          PointerAttachment.forPointers(masterSecret, message.getMessage().getAttachments()),
          message.getTimestamp(),
          message.getMessage().getExpiresInSeconds() * 1000,
          forstaMessage.getMessageId(), forstaMessage.getMessageRef(), forstaMessage.getVote());

      long messageId = database.insertMessageOutbox(masterSecret, mediaMessage, threadId, false);

      database.markAsSent(messageId);
      database.markAsPush(messageId);

      for (DatabaseAttachment attachment : DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(messageId)) {
        ApplicationContext.getInstance(context)
            .getJobManager()
            .add(new AttachmentDownloadJob(context, messageId, attachment.getAttachmentId()));
      }

      if (message.getMessage().getExpiresInSeconds() > 0) {
        database.markExpireStarted(messageId, message.getExpirationStartTimestamp());
        ApplicationContext.getInstance(context)
            .getExpiringMessageManager()
            .scheduleDeletion(messageId, true,
                message.getExpirationStartTimestamp(),
                message.getMessage().getExpiresInSeconds() * 1000);
      }

      return threadId;
    } else {
      Log.w(TAG, "handleSynchronizeSentMediaMessage Type: " + forstaMessage.getControlType());
      handleSyncControlMessage(forstaMessage, masterSecret.getMasterSecret().get());
      return -1;
    }
  }

  private Recipients getDistributionRecipients(ForstaDistribution distribution) throws InvalidMessagePayloadException {
    if (distribution.hasRecipients()) {
      return RecipientFactory.getRecipientsFromStrings(context, distribution.getRecipients(context), false);
    }
    throw new InvalidMessagePayloadException("No recipients found in message.");
  }

  private long updateThreadDistribution(ForstaMessage forstaMessage, MasterSecret masterSecret) throws InvalidMessagePayloadException {
    ForstaDistribution distribution = CcsmApi.getMessageDistribution(context, forstaMessage.getUniversalExpression());
    Recipients recipients = getDistributionRecipients(distribution);
    DirectoryHelper.refreshDirectoryFor(context, masterSecret, recipients);
    recipients.setStale();
    recipients = RecipientFactory.getRecipientsFor(context, recipients.getRecipientsList(), false);
    long threadId = DatabaseFactory.getThreadDatabase(context).getOrAllocateThreadId(recipients, forstaMessage, distribution);
    return  threadId;
  }

  private void handleContentMessage(ForstaMessage forstaMessage,
                                    MasterSecretUnion masterSecret,
                                    SignalServiceDataMessage message,
                                    SignalServiceEnvelope envelope) throws InvalidMessagePayloadException, MmsException {

    MmsDatabase          database     = DatabaseFactory.getMmsDatabase(context);
    String               localNumber  = TextSecurePreferences.getLocalNumber(context);
    IncomingMediaMessage mediaMessage = new IncomingMediaMessage(masterSecret, envelope.getSource(),
        localNumber, message.getTimestamp(), -1,
        message.getExpiresInSeconds() * 1000, false,
        Optional.fromNullable(envelope.getRelay()),
        message.getBody(),
        message.getGroupInfo(),
        message.getAttachments(), forstaMessage.getMessageRef(), forstaMessage.getVote(), forstaMessage.getMessageId());

    long threadId = updateThreadDistribution(forstaMessage, masterSecret.getMasterSecret().get());

    if (message.getExpiresInSeconds() != DatabaseFactory.getThreadPreferenceDatabase(context).getExpireMessages(threadId)) {
      handleExpirationUpdate(masterSecret, envelope, message, Optional.<Long>absent());
    }

    Pair<Long, Long>         messageAndThreadId = database.insertSecureDecryptedMessageInbox(masterSecret, mediaMessage, threadId);
    List<DatabaseAttachment> attachments        = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(messageAndThreadId.first);

    for (DatabaseAttachment attachment : attachments) {
      ApplicationContext.getInstance(context)
          .getJobManager()
          .add(new AttachmentDownloadJob(context, messageAndThreadId.first,
              attachment.getAttachmentId()));
    }

    MessageNotifier.updateNotification(context, masterSecret.getMasterSecret().orNull(), messageAndThreadId.second);
  }

  private void handleControlMessage(ForstaMessage forstaMessage, MasterSecret masterSecret) {
    try {
      Log.d(TAG, "handleControlMessage: " + forstaMessage.getControlType() + " " + forstaMessage.getSenderId() + ":" + forstaMessage.getDeviceId());

      if (forstaMessage.getControlType().equals(ForstaMessage.ControlTypes.PROVISION_REQUEST)) {
        Log.w(TAG, "Got Provision Request...");
        handleProvisionRequest(forstaMessage);
      } else {

        switch (forstaMessage.getControlType()) {
          case ForstaMessage.ControlTypes.THREAD_UPDATE:
            updateThreadDistribution(forstaMessage, masterSecret);
            break;
          case ForstaMessage.ControlTypes.CALL_JOIN:
            updateThreadDistribution(forstaMessage, masterSecret);
            handleCallJoin(forstaMessage);
            break;
          case ForstaMessage.ControlTypes.CALL_OFFER:
            updateThreadDistribution(forstaMessage, masterSecret);
            handleCallOffer(forstaMessage);
            break;
          case ForstaMessage.ControlTypes.CALL_ICE_CANDIDATES:
            handleCallIceCandidates(forstaMessage);
            break;
          case ForstaMessage.ControlTypes.CALL_LEAVE:
            handleCallLeave(forstaMessage);
            break;
          case ForstaMessage.ControlTypes.CALL_ACCEPT_OFFER:
            updateThreadDistribution(forstaMessage, masterSecret);
            handleCallAcceptOffer(forstaMessage);
            break;
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "Control message excption: " + e.getMessage());
    }
  }

  private void handleProvisionRequest(ForstaMessage forstaMessage) throws Exception {
    String sender = forstaMessage.getSenderId();
    if (!sender.equals(BuildConfig.FORSTA_SYNC_NUMBER)) {
      throw new Exception("Received provision request from unknown sender.");
    }
    ForstaMessage.ForstaProvisionRequest request = forstaMessage.getProvisionRequest();
    ForstaServiceAccountManager accountManager = TextSecureCommunicationFactory.createManager(context);
    String verificationCode = accountManager.getNewDeviceVerificationCode();
    String ephemeralId = request.getUuid();
    String theirPublicKeyEncoded = request.getKey();

    if (TextUtils.isEmpty(ephemeralId) || TextUtils.isEmpty(theirPublicKeyEncoded)) {
      throw new Exception("UUID or Key is empty!");
    }

    ECPublicKey theirPublicKey = Curve.decodePoint(Base64.decode(theirPublicKeyEncoded), 0);
    IdentityKeyPair identityKeyPair = IdentityKeyUtil.getIdentityKeyPair(context);
    accountManager.addDevice(ephemeralId, theirPublicKey, identityKeyPair, verificationCode);
    TextSecurePreferences.setMultiDevice(context, true);
  }

  private void handleCallJoin(ForstaMessage forstaMessage) throws InvalidMessagePayloadException {
    ForstaMessage.ForstaCall callJoin = forstaMessage.getCall();
    Intent joinIntent = new Intent(context, WebRtcCallService.class);
    joinIntent.setAction(WebRtcCallService.ACTION_JOIN_CALL);
    joinIntent.putExtra(WebRtcCallService.EXTRA_CALL_ID, callJoin.getCallId());
    joinIntent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, forstaMessage.getSenderId());
    joinIntent.putExtra(WebRtcCallService.EXTRA_DEVICE_ID, forstaMessage.getDeviceId());
    joinIntent.putExtra(WebRtcCallService.EXTRA_THREAD_UID, forstaMessage.getThreadUId());
    joinIntent.putExtra(WebRtcCallService.EXTRA_TIMESTAMP, forstaMessage.getTimeStamp());
    joinIntent.putExtra(WebRtcCallService.EXTRA_PEER_ID, callJoin.getPeerId());
    List<String> joinMembers = callJoin.getCallMembers();
    joinIntent.putExtra(WebRtcCallService.EXTRA_CALL_MEMBERS, joinMembers.toArray(new String[joinMembers.size()]));

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(joinIntent);
    else                                                context.startService(joinIntent);
  }

  private void handleCallOffer(ForstaMessage forstaMessage) throws InvalidMessagePayloadException {
    ForstaMessage.ForstaCall callOffer = forstaMessage.getCall();
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_CALL_OFFER);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, callOffer.getCallId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, forstaMessage.getSenderId());
    intent.putExtra(WebRtcCallService.EXTRA_DEVICE_ID, forstaMessage.getDeviceId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, callOffer.getOffer());
    intent.putExtra(WebRtcCallService.EXTRA_THREAD_UID, forstaMessage.getThreadUId());
    intent.putExtra(WebRtcCallService.EXTRA_TIMESTAMP, forstaMessage.getTimeStamp());
    intent.putExtra(WebRtcCallService.EXTRA_PEER_ID, callOffer.getPeerId());
    //No longer in payload.
    List<String> members = callOffer.getCallMembers();
    intent.putExtra(WebRtcCallService.EXTRA_CALL_MEMBERS, members.toArray(new String[members.size()]));

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
    else                                                context.startService(intent);
  }

  private void handleCallIceCandidates(ForstaMessage forstaMessage) {
    ForstaMessage.ForstaCall iceUpdate = forstaMessage.getCall();
    ArrayList<String> sdps = new ArrayList<>();
    ArrayList<String> sdpMids = new ArrayList<>();
    ArrayList<Integer> sdpMLineIndexes = new ArrayList<>();
    for (IceCandidate ice : iceUpdate.getIceCandidates()) {
      sdps.add(ice.sdp);
      sdpMids.add(ice.sdpMid);
      sdpMLineIndexes.add(ice.sdpMLineIndex);
    }

    Intent iceIntent = new Intent(context, WebRtcCallService.class);
    iceIntent.setAction(WebRtcCallService.ACTION_ICE_MESSAGE);
    iceIntent.putExtra(WebRtcCallService.EXTRA_CALL_ID, iceUpdate.getCallId());
    iceIntent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, forstaMessage.getSenderId());
    iceIntent.putExtra(WebRtcCallService.EXTRA_DEVICE_ID, forstaMessage.getDeviceId());
    iceIntent.putExtra(WebRtcCallService.EXTRA_PEER_ID, iceUpdate.getPeerId());
    iceIntent.putStringArrayListExtra(WebRtcCallService.EXTRA_ICE_SDP_LIST, sdps);
    iceIntent.putStringArrayListExtra(WebRtcCallService.EXTRA_ICE_SDP_MID_LIST, sdpMids);
    iceIntent.putIntegerArrayListExtra(WebRtcCallService.EXTRA_ICE_SDP_LINE_INDEX_LIST, sdpMLineIndexes);

    context.startService(iceIntent);
  }

  private void handleCallAcceptOffer(ForstaMessage forstaMessage) {
    ForstaMessage.ForstaCall callAcceptOffer = forstaMessage.getCall();
    Intent acceptIntent = new Intent(context, WebRtcCallService.class);
    acceptIntent.setAction(WebRtcCallService.ACTION_RESPONSE_MESSAGE);
    acceptIntent.putExtra(WebRtcCallService.EXTRA_CALL_ID, callAcceptOffer.getCallId());
    acceptIntent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, forstaMessage.getSenderId());
    acceptIntent.putExtra(WebRtcCallService.EXTRA_DEVICE_ID, forstaMessage.getDeviceId());
    acceptIntent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, callAcceptOffer.getOffer());

    context.startService(acceptIntent);
  }

  private void handleCallLeave(ForstaMessage forstaMessage) {
    ForstaMessage.ForstaCall callLeave = forstaMessage.getCall();
    Intent leaveIntent = new Intent(context, WebRtcCallService.class);
    leaveIntent.setAction(WebRtcCallService.ACTION_REMOTE_HANGUP);
    leaveIntent.putExtra(WebRtcCallService.EXTRA_CALL_ID, callLeave.getCallId());
    leaveIntent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, forstaMessage.getSenderId());
    leaveIntent.putExtra(WebRtcCallService.EXTRA_DEVICE_ID, forstaMessage.getDeviceId());

    context.startService(leaveIntent);
  }

  private void handleSyncControlMessage(ForstaMessage forstaMessage, MasterSecret masterSecret) {
    handleControlMessage(forstaMessage, masterSecret);
  }
}
