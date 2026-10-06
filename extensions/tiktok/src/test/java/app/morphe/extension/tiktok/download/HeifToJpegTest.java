package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * TikTok 47.1.4 sends a post's photos as HEIF only, and the saved .heif is what #105 couldn't
 * open. Robolectric's Skia reads no HEIF, so a PNG stands in for the photo TikTok sent: the
 * decision is made from the extension the header gave, and the decoding, the JPEG and the swap
 * into the saved file are what's under test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HeifToJpegTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void aHeifPhotoIsSavedAgainAsAJpegOfTheSameSize() throws IOException {
        for (String extension : List.of("heif", "heic")) {
            File photo = png(48, 32, 0xFF3366CC);
            assertEquals(extension + " comes back a JPEG", "jpg", HeifToJpeg.convert(context, photo, extension));
            byte[] saved = Files.readAllBytes(photo.toPath());
            assertEquals("JPEG start of image", 0xFF, saved[0] & 0xFF);
            assertEquals("JPEG start of image", 0xD8, saved[1] & 0xFF);
            Bitmap decoded = BitmapFactory.decodeByteArray(saved, 0, saved.length);
            assertNotNull("the JPEG reads back", decoded);
            assertEquals(48, decoded.getWidth());
            assertEquals(32, decoded.getHeight());
        }
        assertNoConversionFilesLeft();
    }

    @Test
    public void everythingElseKeepsWhatTikTokSent() throws IOException {
        for (String extension : List.of("jpg", "png", "webp", "gif", "avif")) {
            File photo = png(8, 8, 0xFF00AA00);
            byte[] before = Files.readAllBytes(photo.toPath());
            assertEquals(extension, HeifToJpeg.convert(context, photo, extension));
            assertArrayEquals(extension + " untouched", before, Files.readAllBytes(photo.toPath()));
        }
    }

    @Test
    public void aPhotoThatWontDecodeKeepsTheHeif() throws IOException {
        File photo = folder.newFile("broken.tmp");
        byte[] noise = new byte[512];
        new Random(105).nextBytes(noise);
        Files.write(photo.toPath(), noise);
        assertEquals("heif", HeifToJpeg.convert(context, photo, "heif"));
        assertArrayEquals("the HEIF TikTok sent is still there", noise, Files.readAllBytes(photo.toPath()));
        assertNoConversionFilesLeft();
    }

    @Test
    public void aSeeThroughPhotoStaysHeifBecauseJpegWouldBlackenIt() throws IOException {
        File photo = png(8, 8, 0x00000000);
        byte[] before = Files.readAllBytes(photo.toPath());
        assertEquals("heif", HeifToJpeg.convert(context, photo, "heif"));
        assertArrayEquals(before, Files.readAllBytes(photo.toPath()));
        assertNoConversionFilesLeft();
    }

    @Test
    @Config(sdk = 27)
    public void beforeAndroid9TheHeifStays() throws IOException {
        File photo = png(8, 8, 0xFF3366CC);
        byte[] before = Files.readAllBytes(photo.toPath());
        assertEquals("heif", HeifToJpeg.convert(context, photo, "heif"));
        assertArrayEquals(before, Files.readAllBytes(photo.toPath()));
    }

    private File png(int width, int height, int color) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        // Android reads every HEIF as opaque, and the PNG standing in says so only when its
        // bitmap does; otherwise it's written with an alpha channel and decodes see-through.
        bitmap.setHasAlpha(Color.alpha(color) != 0xFF);
        File file = folder.newFile();
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("PNG encode failed");
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private void assertNoConversionFilesLeft() {
        File[] left = new File(context.getCacheDir(), MediaCache.DIRECTORY_NAME)
                .listFiles((directory, name) -> name.startsWith("photo-jpeg-"));
        assertEquals("conversion files left behind", 0, left == null ? 0 : left.length);
    }
}
