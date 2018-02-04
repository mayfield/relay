package io.forsta.ccsm.api;

import android.content.Context;
import android.util.Log;

import com.google.protobuf.InvalidProtocolBufferException;

import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.ecc.ECPrivateKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.signalservice.internal.util.Base64;
import org.whispersystems.signalservice.internal.websocket.WebSocketProtos;

import java.util.Arrays;

import io.forsta.ccsm.ForstaPreferences;
import io.forsta.ccsm.ProvisioningCipher;
import io.forsta.ccsm.service.ForstaServiceAccountManager;
import io.forsta.ccsm.util.WebSocketUtils;
import io.forsta.securesms.crypto.IdentityKeyUtil;
import io.forsta.securesms.push.TextSecureCommunicationFactory;

/**
 * Created by john on 2/2/2018.
 */

// Rename this to AutoProvision or something else.
public class AutoProvision {
  private static final String TAG = AutoProvision.class.getSimpleName();

  private WebSocketUtils webSocket;
  private static AutoProvision instance;
  private Context context;
  private static final Object lock = new Object();
  private ProvisionCallbacks callbacks;

  private AutoProvision(Context context) {
    this.context = context;
  }

  public static AutoProvision getInstance(Context context) {
    synchronized (lock) {
      if (instance == null)
        instance = new AutoProvision(context);

      return instance;
    }
  }

  public void setProvisionCallbacks(ProvisionCallbacks callbacks) {
    this.callbacks = callbacks;
  }

  public void start(final ForstaServiceAccountManager accountManager, final String addr, final String signalingKey, final int registrationId, final String password) {
    webSocket = WebSocketUtils.getInstance(context, new WebSocketUtils.MessageCallbacks() {
      @Override
      public void onSocketMessage(WebSocketProtos.WebSocketRequestMessage request) {
        String path = request.getPath();
        String verb = request.getVerb();

        IdentityKeyPair identityKeys  = IdentityKeyUtil.getIdentityKeyPair(context);
        ECPublicKey ourPubKey = identityKeys.getPublicKey().getPublicKey();
        ECPrivateKey ourPrivKey = identityKeys.getPrivateKey();

        Log.w(TAG, "Our Public and Private keys");
        Log.w(TAG, Arrays.toString(ourPubKey.serialize()));
        Log.w(TAG, Arrays.toString(ourPrivKey.serialize()));

        if (path.equals("/v1/address") && verb.equals("PUT")) {
          Log.w(TAG, "Received address");
          try {
            final ProvisioningProtos.ProvisioningUuid proto = ProvisioningProtos.ProvisioningUuid.parseFrom(request.getBody());
            byte[] serializedPublicKey = ourPubKey.serialize();
            final String encodedKey = Base64.encodeBytes(serializedPublicKey);
            CcsmApi.provisionRequest(context, proto.getUuid(), encodedKey);

          } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
          }
        } else if (path.equals("/v1/message") && verb.equals("PUT")) {
          Log.w(TAG, "Received message");
          try {
            org.whispersystems.signalservice.internal.push.ProvisioningProtos.ProvisionEnvelope envelope = org.whispersystems.signalservice.internal.push.ProvisioningProtos.ProvisionEnvelope.parseFrom(request.getBody());
            ProvisioningCipher provisionCipher = new ProvisioningCipher(null);
            org.whispersystems.signalservice.internal.push.ProvisioningProtos.ProvisionMessage provisionMessage = provisionCipher.decrypt(envelope, ourPrivKey);
            if (provisionMessage != null) {
              if (!provisionMessage.getNumber().equals(ForstaPreferences.getUserId(context))) { // or TextSecurePreferences.getNumber()
                Log.w(TAG, "Received provision message from unknown address");
              }
              Log.w(TAG, "Provisioning message content");
              Log.w(TAG, provisionMessage.getNumber());
              Log.w(TAG, provisionMessage.getProvisioningCode());
              Log.w(TAG, "Private key");
              Log.w(TAG, Arrays.toString(provisionMessage.getIdentityKeyPrivate().toByteArray())); // matched ourPrivKey. Why?
              accountManager.addDevice(provisionMessage.getProvisioningCode(), provisionMessage.getNumber(), signalingKey, registrationId, password);
              if (callbacks != null) {
                callbacks.onComplete();
              }
            } else {
              Log.w(TAG, "Failed to decrypt provision message");
            }
          } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
          }
          webSocket.disconnect();
        }
      }

      @Override
      public void onStatusChanged(boolean connected) {
        Log.w(TAG, "Socket " + (connected ? "Open" : "Closed"));
      }
    });
    webSocket.connect("/v1/websocket/provisioning/");
  }

  public interface ProvisionCallbacks {
    void onStartProvisioning(String uuid);
    void onReceiveProvisionMessage();
    void onComplete();
  }
}
