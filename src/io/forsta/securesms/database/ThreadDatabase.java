/**
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.forsta.securesms.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MergeCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import io.forsta.ccsm.api.model.ForstaDistribution;
import io.forsta.ccsm.api.model.ForstaMessage;
import io.forsta.ccsm.database.model.ForstaThread;
import io.forsta.securesms.R;
import io.forsta.securesms.color.MaterialColor;
import io.forsta.securesms.color.MaterialColors;
import io.forsta.securesms.crypto.MasterCipher;
import io.forsta.securesms.database.MessagingDatabase.MarkedMessageInfo;
import io.forsta.securesms.database.model.DisplayRecord;
import io.forsta.securesms.database.model.MediaMmsMessageRecord;
import io.forsta.securesms.database.model.MessageRecord;
import io.forsta.securesms.database.model.ThreadRecord;
import io.forsta.securesms.mms.Slide;
import io.forsta.securesms.mms.SlideDeck;
import io.forsta.securesms.recipients.Recipient;
import io.forsta.securesms.recipients.RecipientFactory;
import io.forsta.securesms.recipients.Recipients;
import io.forsta.securesms.util.Util;
import org.whispersystems.libsignal.InvalidMessageException;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ThreadDatabase extends Database {

  private static final String TAG = ThreadDatabase.class.getSimpleName();

          static final String TABLE_NAME      = "thread";
  public  static final String ID              = "_id";
  public  static final String DATE            = "date";
  public  static final String MESSAGE_COUNT   = "message_count";
  public  static final String RECIPIENT_IDS   = "recipient_ids";
  public  static final String SNIPPET         = "snippet";
  private static final String SNIPPET_CHARSET = "snippet_cs";
  public  static final String READ            = "read";
  public  static final String TYPE            = "type";
  private static final String ERROR           = "error";
  public  static final String SNIPPET_TYPE    = "snippet_type";
  public  static final String SNIPPET_URI     = "snippet_uri";
  public  static final String ARCHIVED        = "archived";
  public  static final String STATUS          = "status";
  public  static final String RECEIPT_COUNT   = "delivery_receipt_count";
  public  static final String EXPIRES_IN      = "expires_in";
  public static final String DISTRIBUTION = "distribution";
  public static final String TITLE = "title";
  public static final String UID = "uid";

  public static final String CREATE_TABLE = "CREATE TABLE " + TABLE_NAME + " ("                    +
    ID + " INTEGER PRIMARY KEY, " + DATE + " INTEGER DEFAULT 0, "                                  +
    MESSAGE_COUNT + " INTEGER DEFAULT 0, " + RECIPIENT_IDS + " TEXT, " + SNIPPET + " TEXT, "       +
    SNIPPET_CHARSET + " INTEGER DEFAULT 0, " + READ + " INTEGER DEFAULT 1, "                       +
    TYPE + " INTEGER DEFAULT 0, " + ERROR + " INTEGER DEFAULT 0, "                                 +
    SNIPPET_TYPE + " INTEGER DEFAULT 0, " + SNIPPET_URI + " TEXT DEFAULT NULL, "                   +
    ARCHIVED + " INTEGER DEFAULT 0, " + STATUS + " INTEGER DEFAULT 0, "                            +
    RECEIPT_COUNT + " INTEGER DEFAULT 0, " + EXPIRES_IN + " INTEGER DEFAULT 0, " +
      DISTRIBUTION + " TEXT, " +
      TITLE + " TEXT, " +
      UID + " TEXT);";

  public static final String[] CREATE_INDEXS = {
    "CREATE INDEX IF NOT EXISTS thread_recipient_ids_index ON " + TABLE_NAME + " (" + RECIPIENT_IDS + ");",
    "CREATE INDEX IF NOT EXISTS archived_index ON " + TABLE_NAME + " (" + ARCHIVED + ");",
  };

  public ThreadDatabase(Context context, SQLiteOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  private long[] getRecipientIds(Recipients recipients) {
    Set<Long>       recipientSet  = new HashSet<>();
    List<Recipient> recipientList = recipients.getRecipientsList();

    for (Recipient recipient : recipientList) {
      recipientSet.add(recipient.getRecipientId());
    }

    long[] recipientArray = new long[recipientSet.size()];
    int i                 = 0;

    for (Long recipientId : recipientSet) {
      recipientArray[i++] = recipientId;
    }

    Arrays.sort(recipientArray);

    return recipientArray;
  }

  private String getRecipientsAsString(long[] recipientIds) {
    StringBuilder sb = new StringBuilder();
    for (int i=0;i<recipientIds.length;i++) {
      if (i != 0) sb.append(' ');
      sb.append(recipientIds[i]);
    }

    return sb.toString();
  }

  private long createThreadForRecipients(String recipients, int recipientCount, int distributionType) {
    return createThreadForRecipients(recipients, recipientCount, distributionType, null);
  }

  private long createThreadForRecipients(String recipients, int recipientCount, int distributionType, String threadUid) {
    ContentValues contentValues = new ContentValues(5);
    long date                   = System.currentTimeMillis();

    contentValues.put(DATE, date - date % 1000);
    contentValues.put(RECIPIENT_IDS, recipients);

    if (recipientCount > 1)
      contentValues.put(TYPE, distributionType);

    if (threadUid != null) {
      contentValues.put(UID, threadUid);
    }

    contentValues.put(MESSAGE_COUNT, 0);
    contentValues.put(UID, UUID.randomUUID().toString());

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    return db.insert(TABLE_NAME, null, contentValues);
  }

  private void updateThread(long threadId, long count, String body, @Nullable Uri attachment,
                            long date, int status, int receiptCount, long type, boolean unarchive,
                            long expiresIn)
  {
    ContentValues contentValues = new ContentValues(7);
    contentValues.put(DATE, date - date % 1000);
    contentValues.put(MESSAGE_COUNT, count);
    contentValues.put(SNIPPET, body);
    contentValues.put(SNIPPET_URI, attachment == null ? null : attachment.toString());
    contentValues.put(SNIPPET_TYPE, type);
    contentValues.put(STATUS, status);
    contentValues.put(RECEIPT_COUNT, receiptCount);
    contentValues.put(EXPIRES_IN, expiresIn);

    if (unarchive) {
      contentValues.put(ARCHIVED, 0);
    }

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void updateSnippet(long threadId, String snippet, @Nullable Uri attachment, long date, long type, boolean unarchive) {
    ContentValues contentValues = new ContentValues(4);

    contentValues.put(DATE, date - date % 1000);
    contentValues.put(SNIPPET, snippet);
    contentValues.put(SNIPPET_TYPE, type);
    contentValues.put(SNIPPET_URI, attachment == null ? null : attachment.toString());

    if (unarchive) {
      contentValues.put(ARCHIVED, 0);
    }

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  private void deleteThread(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  private void deleteThreads(Set<Long> threadIds) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    String where      = "";

    for (long threadId : threadIds) {
      where += ID + " = '" + threadId + "' OR ";
    }

    where = where.substring(0, where.length() - 4);

    db.delete(TABLE_NAME, where, null);
    notifyConversationListListeners();
  }

  private void deleteAllThreads() {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.delete(TABLE_NAME, null, null);
    notifyConversationListListeners();
  }

  public void trimAllThreads(int length, ProgressListener listener) {
    Cursor cursor   = null;
    int threadCount = 0;
    int complete    = 0;

    try {
      cursor = this.getConversationList();

      if (cursor != null)
        threadCount = cursor.getCount();

      while (cursor != null && cursor.moveToNext()) {
        long threadId = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
        trimThread(threadId, length);

        listener.onProgress(++complete, threadCount);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public void trimThread(long threadId, int length) {
    Log.w("ThreadDatabase", "Trimming thread: " + threadId + " to: " + length);
    Cursor cursor = null;

    try {
      cursor = DatabaseFactory.getMmsSmsDatabase(context).getConversation(threadId);

      // Forsta message trimming.
      if (cursor != null && length > 0) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -30);
        long cutOffDate = cal.getTimeInMillis();

        DatabaseFactory.getSmsDatabase(context).deleteMessagesInThreadBeforeDate(threadId, cutOffDate);
        DatabaseFactory.getMmsDatabase(context).deleteMessagesInThreadBeforeDate(threadId, cutOffDate);

        update(threadId, false);
        notifyConversationListeners(threadId);
      }

      if (cursor != null && length > 0 && cursor.getCount() > length) {
        Log.w("ThreadDatabase", "Cursor count is greater than length!");
        cursor.moveToPosition(length - 1);

        long lastTweetDate = cursor.getLong(cursor.getColumnIndexOrThrow(MmsSmsColumns.NORMALIZED_DATE_RECEIVED));

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -30);
        long cutOffDate = cal.getTimeInMillis();

        Log.w("ThreadDatabase", "Cut off tweet date: " + lastTweetDate);

        DatabaseFactory.getSmsDatabase(context).deleteMessagesInThreadBeforeDate(threadId, lastTweetDate);
        DatabaseFactory.getMmsDatabase(context).deleteMessagesInThreadBeforeDate(threadId, lastTweetDate);

        update(threadId, false);
        notifyConversationListeners(threadId);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public void setAllThreadsRead() {
    SQLiteDatabase db           = databaseHelper.getWritableDatabase();
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 1);

    db.update(TABLE_NAME, contentValues, null, null);

    DatabaseFactory.getSmsDatabase(context).setAllMessagesRead();
    DatabaseFactory.getMmsDatabase(context).setAllMessagesRead();
    notifyConversationListListeners();
  }

  public List<MarkedMessageInfo> setRead(long threadId) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 1);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId+""});

    final List<MarkedMessageInfo> smsRecords = DatabaseFactory.getSmsDatabase(context).setMessagesRead(threadId);
    final List<MarkedMessageInfo> mmsRecords = DatabaseFactory.getMmsDatabase(context).setMessagesRead(threadId);

    notifyConversationListListeners();

    return new LinkedList<MarkedMessageInfo>() {{
      addAll(smsRecords);
      addAll(mmsRecords);
    }};
  }

  public void setUnread(long threadId) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(READ, 0);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void setDistributionType(long threadId, int distributionType) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(TYPE, distributionType);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public Cursor getFilteredConversationList(String filter) {
    SQLiteDatabase   db                      = databaseHelper.getReadableDatabase();
    List<Cursor>     cursors                 = new LinkedList<>();
    String titleSelection = TITLE + " LIKE ? ";
    String filterQuery = "%" + filter + "%";

    cursors.add(db.query(TABLE_NAME, null, titleSelection, new String[] {filterQuery}, null, null, DATE + " DESC"));

    Cursor cursor = cursors.size() > 1 ? new MergeCursor(cursors.toArray(new Cursor[cursors.size()])) : cursors.get(0);
    setNotifyConverationListListeners(cursor);
    return cursor;
  }

  public Cursor getConversationList() {
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor         cursor =  db.query(TABLE_NAME, null, ARCHIVED + " = ?", new String[] {"0"}, null, null, DATE + " DESC");
    setNotifyConverationListListeners(cursor);
    return cursor;
  }

  public Cursor getArchivedConversationList() {
    SQLiteDatabase db     = databaseHelper.getReadableDatabase();
    Cursor         cursor = db.query(TABLE_NAME, null, ARCHIVED + " = ?", new String[] {"1"}, null, null, DATE + "");

    setNotifyConverationListListeners(cursor);

    return cursor;
  }

  public int getArchivedConversationListCount() {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, new String[] {"COUNT(*)"}, ARCHIVED + " = ?",
                        new String[] {"1"}, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0);
      }

    } finally {
      if (cursor != null) cursor.close();
    }

    return 0;
  }

  public void archiveConversation(long threadId) {
    SQLiteDatabase db            = databaseHelper.getWritableDatabase();
    ContentValues  contentValues = new ContentValues(1);
    contentValues.put(ARCHIVED, 1);

    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void unarchiveConversation(long threadId) {
    SQLiteDatabase db            = databaseHelper.getWritableDatabase();
    ContentValues  contentValues = new ContentValues(1);
    contentValues.put(ARCHIVED, 0);

    db.update(TABLE_NAME, contentValues, ID_WHERE, new String[] {threadId + ""});
    notifyConversationListListeners();
  }

  public void deleteConversation(long threadId) {
    DatabaseFactory.getSmsDatabase(context).deleteThread(threadId);
    DatabaseFactory.getMmsDatabase(context).deleteThread(threadId);
    DatabaseFactory.getDraftDatabase(context).clearDrafts(threadId);
    DatabaseFactory.getThreadPreferenceDatabase(context).deleteThreadPreference(threadId);
    deleteThread(threadId);
    notifyConversationListeners(threadId);
    notifyConversationListListeners();
  }

  public void deleteConversations(Set<Long> selectedConversations) {
    DatabaseFactory.getSmsDatabase(context).deleteThreads(selectedConversations);
    DatabaseFactory.getMmsDatabase(context).deleteThreads(selectedConversations);
    DatabaseFactory.getDraftDatabase(context).clearDrafts(selectedConversations);
    DatabaseFactory.getThreadPreferenceDatabase(context).deleteThreadPreferences(selectedConversations);
    deleteThreads(selectedConversations);
    notifyConversationListeners(selectedConversations);
    notifyConversationListListeners();
  }

  public void deleteAllConversations() {
    DatabaseFactory.getSmsDatabase(context).deleteAllThreads();
    DatabaseFactory.getMmsDatabase(context).deleteAllThreads();
    DatabaseFactory.getDraftDatabase(context).clearAllDrafts();
    deleteAllThreads();
  }

  public long getThreadIdIfExistsFor(Recipients recipients) {
    long[] recipientIds    = getRecipientIds(recipients);
    String recipientsList  = getRecipientsAsString(recipientIds);
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    String where           = RECIPIENT_IDS + " = ?";
    String[] recipientsArg = new String[] {recipientsList};
    Cursor cursor          = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      else
        return -1L;
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public long getThreadIdFor(Recipients recipients) {
    return getThreadIdFor(recipients, DistributionTypes.DEFAULT);
  }

  public long getThreadIdFor(Recipients recipients, int distributionType) {
    long[] recipientIds    = getRecipientIds(recipients);
    String recipientsList  = getRecipientsAsString(recipientIds);
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    String where           = RECIPIENT_IDS + " = ?";
    String[] recipientsArg = new String[] {recipientsList};
    Cursor cursor          = null;

    try {
      cursor = db.query(TABLE_NAME, new String[]{ID}, where, recipientsArg, null, null, null);

      if (cursor != null && cursor.moveToFirst())
        return cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      else
        return createThreadForRecipients(recipientsList, recipientIds.length, distributionType);
    } finally {
      if (cursor != null)
        cursor.close();
    }
  }

  public ForstaThread getThreadForDistribution(String distribution) {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    Cursor cursor          = null;
    try {
      cursor = db.query(TABLE_NAME, null, DISTRIBUTION + " = ? ", new String[]{distribution + ""}, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return new ForstaThread(cursor);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
    return null;
  }

  public ForstaThread allocateThread(Recipients recipients, ForstaDistribution distribution) {
    long[] recipientIds    = getRecipientIds(recipients);
    String recipientsList  = getRecipientsAsString(recipientIds);
    ContentValues contentValues = new ContentValues(5);
    long date                   = System.currentTimeMillis();

    contentValues.put(DATE, date - date % 1000);
    contentValues.put(RECIPIENT_IDS, recipientsList);
    contentValues.put(TYPE, DistributionTypes.DEFAULT);
    contentValues.put(UID, UUID.randomUUID().toString());
    contentValues.put(DISTRIBUTION, distribution.universal);
    contentValues.put(MESSAGE_COUNT, 0);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    long threadId = db.insert(TABLE_NAME, null, contentValues);
//    DatabaseFactory.getThreadPreferenceDatabase(context).setDefaultColor(threadId);
    return getForstaThread(threadId);
  }

  public long allocateThreadId(Recipients recipients, ForstaMessage forstaMessage) {
    long[] recipientIds    = getRecipientIds(recipients);
    String recipientsList  = getRecipientsAsString(recipientIds);
    ContentValues contentValues = new ContentValues(5);
    long date                   = System.currentTimeMillis();

    contentValues.put(DATE, date - date % 1000);
    contentValues.put(RECIPIENT_IDS, recipientsList);
    contentValues.put(TYPE, DistributionTypes.DEFAULT);
    contentValues.put(UID, forstaMessage.getThreadUId());
    contentValues.put(DISTRIBUTION, forstaMessage.getUniversalExpression());
    contentValues.put(TITLE, forstaMessage.getThreadTitle());
    contentValues.put(MESSAGE_COUNT, 0);

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    long threadId = db.insert(TABLE_NAME, null, contentValues);
//    DatabaseFactory.getThreadPreferenceDatabase(context).setDefaultColor(threadId);
    return threadId;
  }

  public long getThreadIdForUid(String threadUid) {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    Cursor cursor          = null;
    try {
      cursor = db.query(TABLE_NAME, null, UID + " = ? ", new String[]{threadUid + ""}, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(ID));
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }
    return -1;
  }

  public @Nullable Recipients getRecipientsForThreadId(long threadId) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    Cursor cursor     = null;

    try {
      cursor = db.query(TABLE_NAME, null, ID + " = ?", new String[] {threadId+""}, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {
        String recipientIds = cursor.getString(cursor.getColumnIndexOrThrow(RECIPIENT_IDS));
        return RecipientFactory.getRecipientsForIds(context, recipientIds, false);
      }
    } finally {
      if (cursor != null)
        cursor.close();
    }

    return null;
  }

  public void updateReadState(long threadId) {
    int unreadCount = DatabaseFactory.getMmsSmsDatabase(context).getUnreadCount(threadId);

    ContentValues contentValues = new ContentValues();
    contentValues.put(READ, unreadCount == 0);

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues,ID_WHERE,
                                                new String[] {String.valueOf(threadId)});

    notifyConversationListListeners();
  }

  public boolean update(long threadId, boolean unarchive) {
    MmsSmsDatabase mmsSmsDatabase = DatabaseFactory.getMmsSmsDatabase(context);
    long count                    = mmsSmsDatabase.getConversationCount(threadId);

    if (count == 0) {
      deleteThread(threadId);
      notifyConversationListListeners();
      return true;
    }

    MmsSmsDatabase.Reader reader = null;

    try {
      reader = mmsSmsDatabase.readerFor(mmsSmsDatabase.getConversationSnippet(threadId));
      MessageRecord record;

      if (reader != null && (record = reader.getNext()) != null) {
        updateThread(threadId, count, record.getBody().getBody(), getAttachmentUriFor(record),
                     record.getTimestamp(), record.getDeliveryStatus(), record.getReceiptCount(),
                     record.getType(), unarchive, record.getExpiresIn());
        notifyConversationListListeners();
        return false;
      } else {
        deleteThread(threadId);
        notifyConversationListListeners();
        return true;
      }
    } finally {
      if (reader != null)
        reader.close();
    }
  }

  public void updateForstaThread(long threadId, Recipients recipients, String distribution, String title) {
    long[] recipientIds    = getRecipientIds(recipients);
    String recipientsList  = getRecipientsAsString(recipientIds);
    ForstaThread forstaThread = getForstaThread(threadId);
    ContentValues values = new ContentValues();
    if (!TextUtils.equals(forstaThread.title, title)) {
      values.put(TITLE, title);
    }
    if (!TextUtils.isEmpty(distribution) && !distribution.equals(forstaThread.distribution)) {
      values.put(RECIPIENT_IDS, recipientsList);
      values.put(DISTRIBUTION, distribution);
    }
    if (values.size() > 0) {
      SQLiteDatabase db = databaseHelper.getWritableDatabase();
      db.update(TABLE_NAME, values, ID + " = ?", new String[] {threadId + ""});
      notifyConversationListListeners();
    }
  }

  public ForstaThread getForstaThread(long threadId) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    Cursor cursor = null;
    try {
      cursor = db.query(TABLE_NAME, null, ID + " = ? ", new String[]{threadId + ""}, null, null, null);
      if (cursor != null && cursor.moveToFirst()) {
        return new ForstaThread(cursor);
      }
    } finally {
      cursor.close();
    }
    return null;
  }

  public void updateThreadTitle(long threadId, String title) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    ContentValues values = new ContentValues(1);
    values.put(TITLE, title);
    db.update(TABLE_NAME, values, ID + " = ?", new String[] {threadId + ""});
  }

  private @Nullable Uri getAttachmentUriFor(MessageRecord record) {
    if (!record.isMms() || record.isMmsNotification() || record.isGroupAction()) return null;

    SlideDeck slideDeck = ((MediaMmsMessageRecord)record).getSlideDeck();
    Slide thumbnail = slideDeck.getThumbnailSlide();

    return thumbnail != null ? thumbnail.getThumbnailUri() : null;
  }

  public static interface ProgressListener {
    public void onProgress(int complete, int total);
  }

  public Reader readerFor(Cursor cursor, MasterCipher masterCipher) {
    return new Reader(cursor, masterCipher);
  }

  public static class DistributionTypes {
    public static final int DEFAULT      = 2;
    public static final int BROADCAST    = 1;
    public static final int CONVERSATION = 2;
    public static final int ARCHIVE      = 3;
  }

  public class Reader {

    private final Cursor       cursor;
    private final MasterCipher masterCipher;

    public Reader(Cursor cursor, MasterCipher masterCipher) {
      this.cursor       = cursor;
      this.masterCipher = masterCipher;
    }

    public ThreadRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    public ThreadRecord getCurrent() {
      long       threadId    = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.ID));
      String     recipientId = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.RECIPIENT_IDS));
      Recipients recipients  = RecipientFactory.getRecipientsForIds(context, recipientId, true);

      DisplayRecord.Body body = getPlaintextBody(cursor);
      long date               = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.DATE));
      long count              = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.MESSAGE_COUNT));
      long read               = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.READ));
      long type               = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_TYPE));
      int distributionType    = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.TYPE));
      boolean archived        = cursor.getInt(cursor.getColumnIndex(ThreadDatabase.ARCHIVED)) != 0;
      int status              = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.STATUS));
      int receiptCount        = cursor.getInt(cursor.getColumnIndexOrThrow(ThreadDatabase.RECEIPT_COUNT));
      long expiresIn          = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.EXPIRES_IN));
      String distribution = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.DISTRIBUTION));
      String title = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.TITLE));
      String threadUid = cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.UID));
      Uri snippetUri          = getSnippetUri(cursor);

      return new ThreadRecord(context, body, snippetUri, recipients, date, count, read == 1,
                              threadId, receiptCount, status, type, distributionType, archived,
                              expiresIn, distribution, title, threadUid);
    }

    private DisplayRecord.Body getPlaintextBody(Cursor cursor) {
      try {
        long type   = cursor.getLong(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_TYPE));
        String body = cursor.getString(cursor.getColumnIndexOrThrow(SNIPPET));

        if (!TextUtils.isEmpty(body) && masterCipher != null && MmsSmsColumns.Types.isSymmetricEncryption(type)) {
          return new DisplayRecord.Body(masterCipher.decryptBody(body), true);
        } else if (!TextUtils.isEmpty(body) && masterCipher == null && MmsSmsColumns.Types.isSymmetricEncryption(type)) {
          return new DisplayRecord.Body(body, false);
        } else {
          return new DisplayRecord.Body(body, true);
        }
      } catch (InvalidMessageException e) {
        Log.w("ThreadDatabase", e);
        return new DisplayRecord.Body(context.getString(R.string.ThreadDatabase_error_decrypting_message), true);
      }
    }

    private @Nullable Uri getSnippetUri(Cursor cursor) {
      if (cursor.isNull(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_URI))) {
        return null;
      }

      try {
        return Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(ThreadDatabase.SNIPPET_URI)));
      } catch (IllegalArgumentException e) {
        Log.w(TAG, e);
        return null;
      }
    }

    public void close() {
      cursor.close();
    }
  }
}
