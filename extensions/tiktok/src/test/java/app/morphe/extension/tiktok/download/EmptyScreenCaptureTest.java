package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * #551: a screen recording that stopped before its first picture left its folder behind, and
 * that folder refused every later Record with "The recording couldn't be saved".
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class EmptyScreenCaptureTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Rule public SettingsContextRule settings = new SettingsContextRule();
    Context app;
    RecordingRetention store;

    @Before
    public void setup() throws Exception {
        File durable = files.newFolder("durable"), cache = files.newFolder("cache");
        app =
                new ContextWrapper(RuntimeEnvironment.getApplication()) {
                    @Override public File getNoBackupFilesDir() {
                        return durable;
                    }

                    @Override public File getCacheDir() {
                        return cache;
                    }

                    @Override public Context getApplicationContext() {
                        return this;
                    }
                };
        store = RecordingRetention.in(app);
        RecordingRetentionTest.Provider.reset(files.newFolder("provider"), app.getPackageName());
        Robolectric.setupContentProvider(RecordingRetentionTest.Provider.class, "media");
    }

    /** The journal and file a screen capture leaves, with or without its first picture. */
    RecordingRetention.Record capture(boolean picture) throws Exception {
        RecordingRetention.Record record = store.begin("flv", "creator", 1760480102000L);
        record.update("capture", true, "captureComplete", false, "screenAudio", "VIDEO_ONLY");
        try (CaptureRecoveryTest.Output out = new CaptureRecoveryTest.Output(record.source())) {
            CaptureFlv.Writer writer =
                    new CaptureFlv.Writer(
                            new CaptureJournal(record, out, LiveRecorder.ScreenAudio.VIDEO_ONLY),
                            CaptureFlvTest.config(false));
            if (picture) {
                byte[] key = CaptureRecoveryTest.frame(true);
                writer.video(ByteBuffer.wrap(key), 0, key.length, 0);
            }
            // What ScreenSource's pump writes on its way out, whatever ended it.
            writer.checkpoint();
        }
        record.update("captureClosed", true);
        return record;
    }

    static LiveRecorder.Result result(LiveRecorder.Outcome outcome) {
        return new LiveRecorder.Result(outcome, LiveRecorder.End.DISCONNECTED, 0, null, null, null)
                .withScreenAudio(LiveRecorder.ScreenAudio.VIDEO_ONLY);
    }

    @Test
    public void aScreenCaptureWithNoPictureLeavesNothingToBlockTheNextRecording() throws Exception {
        RecordingRetention.Record record = capture(false);
        assertTrue("the header alone is on disk", record.source().length() > 0);
        AtomicReference<LiveRecorder.Result> told = new AtomicReference<>();
        LiveRecordingSaver.finished(record, result(LiveRecorder.Outcome.NOTHING_SAVED), told::set);
        assertNull("nothing is offered for Retry", told.get().retainedId);
        assertTrue(store.ids().isEmpty());
        assertFalse(record.directory.exists());
        store.begin("flv", "next", 1760480103000L).close();
    }

    @Test
    public void aScreenCaptureWithAPictureStaysForRetryAndSaysSoOnTheNextRecord() throws Exception {
        RecordingRetention.Record record = capture(true);
        AtomicReference<LiveRecorder.Result> told = new AtomicReference<>();
        // Even told it saved nothing, a file holding video is never thrown away.
        LiveRecordingSaver.finished(record, result(LiveRecorder.Outcome.NOTHING_SAVED), told::set);
        assertEquals(record.id, told.get().retainedId);
        assertEquals(java.util.Collections.singletonList(record.id), store.ids());
        try {
            store.begin("flv", "next", 1760480103000L).close();
            fail("a retained recording admitted another");
        } catch (IOException refused) {
            assertTrue(refused instanceof RecordingRetention.RetainedRecordings);
            assertEquals("An earlier recording is waiting. Retry or delete it.",
                    LiveRecordingSaver.cantStartText(refused));
        }
    }

    @Test
    public void anEmptyCaptureAnOlderVersionLeftClearsWhenTikTokStarts() throws Exception {
        RecordingRetention.Record record = capture(false);
        String id = record.id;
        record.close();
        assertEquals("left behind, as before", java.util.Collections.singletonList(id), store.ids());
        assertTrue(LiveRecordingSaver.recoverRetained(app).isEmpty());
        assertTrue(store.ids().isEmpty());
        store.begin("flv", "next", 1760480103000L).close();
    }

    @Test
    public void aCaptureWithVideoIsStillRecoveredAtStart() throws Exception {
        RecordingRetention.Record record = capture(true);
        String id = record.id;
        record.close();
        java.util.List<LiveRecorder.Result> results = LiveRecordingSaver.recoverRetained(app);
        assertEquals(1, results.size());
        LiveRecorder.Result result = results.get(0);
        // Published, or kept for Retry when this JVM can't write the MP4; never dropped.
        assertTrue(result.outcome == LiveRecorder.Outcome.SAVED_PARTIAL
                || result.outcome == LiveRecorder.Outcome.FAILED && id.equals(result.retainedId)
                        && store.ids().contains(id));
    }

    @Test
    public void eachReasonRecordCantStartIsSaidInItsOwnWords() {
        assertEquals("Saving the recording",
                LiveRecordingSaver.cantStartText(new RecordingRetention.BackgroundSaveLimit()));
        assertEquals("There isn't enough room on the phone to record",
                LiveRecordingSaver.cantStartText(new RecordingRetention.NotEnoughRoom()));
        assertEquals("The recording couldn't start. Try again.",
                LiveRecordingSaver.cantStartText(new IOException("Recording recovery storage unavailable")));
    }
}
