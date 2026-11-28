package app.morphe.extension.tiktok.spoof.sim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.TelephonyManager;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowTelephonyManager;

/**
 * #415: with the US preset on a phone on a Bangladeshi network, 317 of 340 image fetches came
 * back 403 until a VPN was on. TikTok's network stack was routing by the preset. Its getters'
 * answers come through here, and each one still holding the preset gets the phone's own SIM
 * back, while anything else, and everything with the spoof off or no SIM in the phone, stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class NetworkStackSimTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String INIT =
        "carrierregion=US;mccmnc=310260;sysregion=BD;appregion=US";

    @Before public void setUp() {
        ImageLoads.resetForTests();
        Settings.SIM_SPOOF.save(true);
        Settings.SIM_SPOOF_ISO.save("us");
        Settings.SIMSPOOF_MCCMNC.save("310260");
        phone("bd", "bd", "47001", "47001");
    }

    @After public void tearDown() {
        ImageLoads.resetForTests();
        Settings.SIM_SPOOF.resetToDefault();
        Settings.SIM_SPOOF_ISO.resetToDefault();
        Settings.SIMSPOOF_MCCMNC.resetToDefault();
    }

    private static void phone(String simCountry, String networkCountry, String simOperator, String networkOperator) {
        TelephonyManager phone = (TelephonyManager)
            RuntimeEnvironment.getApplication().getSystemService(Context.TELEPHONY_SERVICE);
        ShadowTelephonyManager shadow = Shadows.shadowOf(phone);
        shadow.setSimCountryIso(simCountry);
        shadow.setNetworkCountryIso(networkCountry);
        shadow.setSimOperator(simOperator);
        shadow.setNetworkOperator(networkOperator);
    }

    @Test public void thePresetGoesBackToTheRealSimInTheCaseTikTokWroteIt() {
        assertEquals("BD", NetworkStackSim.region("US"));
        assertEquals("bd", NetworkStackSim.region("us"));
        assertEquals("47001", NetworkStackSim.networkOperator("310260"));
        assertEquals("47001", NetworkStackSim.simOperator("310260"));
        assertEquals("carrierregion=BD;mccmnc=47001;sysregion=BD;appregion=BD", NetworkStackSim.initRegion(INIT));
        assertEquals(4 + 3, NetworkStackSim.swapped());
    }

    @Test public void anAnswerThatIsNotThePresetStays() {
        // The app region falls back to the language setting's region when there's no carrier one.
        assertEquals("GB", NetworkStackSim.region("GB"));
        assertEquals("", NetworkStackSim.region(""));
        assertNull(NetworkStackSim.region(null));
        assertEquals("", NetworkStackSim.networkOperator(""));
        assertEquals("23415", NetworkStackSim.simOperator("23415"));
        assertEquals("carrierregion=;mccmnc=;sysregion=US;appregion=GB",
            NetworkStackSim.initRegion("carrierregion=;mccmnc=;sysregion=US;appregion=GB"));
        assertEquals("not a header", NetworkStackSim.initRegion("not a header"));
        assertEquals(0, NetworkStackSim.swapped());
    }

    @Test public void theSystemRegionIsTheLanguageSettingAndStaysEvenWhenItMatches() {
        assertEquals("carrierregion=BD;mccmnc=47001;sysregion=US;appregion=BD",
            NetworkStackSim.initRegion("carrierregion=US;mccmnc=310260;sysregion=US;appregion=US"));
    }

    @Test public void withTheSpoofOffNothingChanges() {
        Settings.SIM_SPOOF.save(false);
        assertEquals("US", NetworkStackSim.region("US"));
        assertEquals("310260", NetworkStackSim.networkOperator("310260"));
        assertEquals(INIT, NetworkStackSim.initRegion(INIT));
        assertEquals(0, NetworkStackSim.swapped());
    }

    @Test public void aPhoneWithNoSimKeepsWhatItWasTold() {
        phone("", "", "", "");
        assertEquals("US", NetworkStackSim.region("US"));
        assertEquals("310260", NetworkStackSim.networkOperator("310260"));
        assertEquals(INIT, NetworkStackSim.initRegion(INIT));
    }

    @Test public void theNetworkWinsWhereTheSimIsBlankAndTheOtherWayRound() {
        phone("", "bd", "", "47002");
        assertEquals("BD", NetworkStackSim.region("US"));
        assertEquals("47002", NetworkStackSim.networkOperator("310260"));
        assertEquals("47002", NetworkStackSim.simOperator("310260"));
        phone("bd", "lk", "47001", "41302");
        assertEquals("BD", NetworkStackSim.region("US"));
        assertEquals("41302", NetworkStackSim.networkOperator("310260"));
        assertEquals("47001", NetworkStackSim.simOperator("310260"));
    }

    /** #523: with a Chinese SIM the stack routed by CN and TikTok said "No internet connection". */
    @Test public void aSimFromWhereTikTokIsNotOfferedKeepsThePresetEverywhere() {
        phone("cn", "cn", "46001", "46001");
        assertEquals("US", NetworkStackSim.region("US"));
        assertEquals("us", NetworkStackSim.region("us"));
        assertEquals("310260", NetworkStackSim.networkOperator("310260"));
        assertEquals("310260", NetworkStackSim.simOperator("310260"));
        assertEquals(INIT, NetworkStackSim.initRegion(INIT));
        phone("HK ", "", "45400", "");
        assertEquals("US", NetworkStackSim.region("US"));
        assertEquals("310260", NetworkStackSim.simOperator("310260"));
        assertEquals(0, NetworkStackSim.swapped());
    }

    @Test public void aSimRoamingWhereTikTokIsNotOfferedKeepsThePresetToo() {
        phone("bd", "in", "47001", "40445");
        assertEquals("US", NetworkStackSim.region("US"));
        assertEquals("310260", NetworkStackSim.networkOperator("310260"));
        assertEquals(INIT, NetworkStackSim.initRegion(INIT));
        assertEquals(0, NetworkStackSim.swapped());
    }

    /** Through a VPN TikTok sees the VPN's address, which the preset matches (#415 loaded with one). */
    @Test public void aVpnKeepsThePreset() {
        ConnectivityManager connectivity = (ConnectivityManager)
            RuntimeEnvironment.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities vpn = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(vpn).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        Shadows.shadowOf(vpn).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        Shadows.shadowOf(connectivity).setNetworkCapabilities(active, vpn);
        assertEquals("US", NetworkStackSim.region("US"));
        assertEquals("310260", NetworkStackSim.networkOperator("310260"));
        assertEquals(INIT, NetworkStackSim.initRegion(INIT));
        assertEquals(0, NetworkStackSim.swapped());

        NetworkCapabilities wifi = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(wifi).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        Shadows.shadowOf(connectivity).setNetworkCapabilities(active, wifi);
        assertEquals("BD", NetworkStackSim.region("US"));
    }

    @Test public void anUnusablePresetIsNotOneTikTokWasToldSoNothingIsSwapped() {
        Settings.SIM_SPOOF_ISO.save("zz");
        Settings.SIMSPOOF_MCCMNC.save("31026a");
        assertEquals("ZZ", NetworkStackSim.region("ZZ"));
        assertEquals("31026a", NetworkStackSim.networkOperator("31026a"));
    }

    /** Stands in for TikTok's HttpDnsResult: public fields, a blank constructor and a full one. */
    public static class Address {
        public String domain;
        public String ipList;
        public Long validTime;

        public Address() {
            this(null, null, 0L);
        }

        Address(String domain, String ipList, Long validTime) {
            this.domain = domain;
            this.ipList = ipList;
            this.validTime = validTime;
        }
    }

    /** An entry that can't be made blank. */
    public static class Unbuildable {
        public String domain;

        Unbuildable(String domain) {
            this.domain = domain;
        }
    }

    private static List<Object> feedAddresses() {
        return Arrays.asList(
            new Address("P16-Common-Sign.tiktokcdn.com", "23.0.0.1,23.0.0.2", 600L),
            new Address("p19-common-sign.tiktokcdn.com", "23.0.0.3", 600L),
            new Address("p16-common-sign.tiktokcdn.com", "23.0.0.4", 600L));
    }

    /**
     * #415 on 0.85.0: the stack was on the real SIM (30 answers back) and 65 of 95 images still
     * failed through TikTok's image loader. The feed's own image server addresses were picked for
     * a request carrying the preset. While the stack is on the real SIM they're set aside as one
     * blank entry, which empties TikTok's address table and adds nothing to it.
     */
    @Test public void theFeedsImageServerAddressesAreSetAsideWhileTheStackIsOnTheRealSim() {
        List<?> kept = NetworkStackSim.imageAddresses(feedAddresses());
        assertEquals(1, kept.size());
        Address blank = (Address) kept.get(0);
        assertNull(blank.domain);
        assertNull(blank.ipList);
        assertEquals(1, NetworkStackSim.addressListsSetAside());
        assertEquals(0, NetworkStackSim.addressListsUsed());
        assertEquals(new LinkedHashSet<>(Arrays.asList("p16-common-sign.tiktokcdn.com", "p19-common-sign.tiktokcdn.com")),
            NetworkStackSim.addressHosts());
        assertEquals(0, NetworkStackSim.swapped());
    }

    @Test public void whereverTheStackKeepsThePresetTheFeedsAddressesGoThrough() {
        List<Object> addresses = feedAddresses();
        Settings.SIM_SPOOF.save(false);
        assertSame(addresses, NetworkStackSim.imageAddresses(addresses));
        Settings.SIM_SPOOF.save(true);
        phone("cn", "cn", "46001", "46001");
        assertSame(addresses, NetworkStackSim.imageAddresses(addresses));
        phone("us", "us", "310260", "310260");
        assertSame(addresses, NetworkStackSim.imageAddresses(addresses));
        phone("", "", "", "");
        assertSame(addresses, NetworkStackSim.imageAddresses(addresses));
        assertEquals(4, NetworkStackSim.addressListsUsed());
        assertEquals(0, NetworkStackSim.addressListsSetAside());

        ConnectivityManager connectivity = (ConnectivityManager)
            RuntimeEnvironment.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities vpn = ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(vpn).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        Shadows.shadowOf(connectivity).setNetworkCapabilities(connectivity.getActiveNetwork(), vpn);
        phone("bd", "bd", "47001", "47001");
        assertSame(addresses, NetworkStackSim.imageAddresses(addresses));
    }

    @Test public void noListOrAnEmptyOneIsLeftAsItCame() {
        assertNull(NetworkStackSim.imageAddresses(null));
        List<Object> empty = new ArrayList<>();
        assertSame(empty, NetworkStackSim.imageAddresses(empty));
        assertEquals(0, NetworkStackSim.addressListsUsed() + NetworkStackSim.addressListsSetAside());
    }

    /** No blank entry to be had: no list at all, which leaves TikTok's table as it was. */
    @Test public void anEntryThatCantBeMadeBlankSetsTheListAsideAsNone() {
        assertNull(NetworkStackSim.imageAddresses(Arrays.asList(new Unbuildable("p16-sg.tiktokcdn.com"))));
        assertEquals(1, NetworkStackSim.addressListsSetAside());
        assertNull(NetworkStackSim.imageAddresses(Arrays.asList((Object) null)));
        assertNull(NetworkStackSim.imageAddresses(Arrays.asList("not an address")));
    }

    @Test public void onlyPlainHostNamesReachTheExportAndTheyStayBounded() {
        Settings.SIM_SPOOF.save(false);
        NetworkStackSim.imageAddresses(Arrays.asList(
            new Address("evil.example/path?token=1", "1.1.1.1", 1L),
            new Address("two\nlines.example", "1.1.1.1", 1L),
            new Address("", "1.1.1.1", 1L)));
        assertTrue(NetworkStackSim.addressHosts().isEmpty());
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < NetworkStackSim.ADDRESS_HOST_LIMIT + 10; i++) {
            many.add(new Address("p" + i + ".tiktokcdn.com", "1.1.1.1", 1L));
        }
        NetworkStackSim.imageAddresses(many);
        assertEquals(NetworkStackSim.ADDRESS_HOST_LIMIT, NetworkStackSim.addressHosts().size());
    }

    @Test public void theExportSaysWhatHappenedToTheFeedsAddressesAndMarksTheirHosts() {
        NetworkStackSim.imageAddresses(feedAddresses());
        NetworkStackSim.imageAddresses(feedAddresses());
        Settings.SIM_SPOOF.save(false);
        NetworkStackSim.imageAddresses(feedAddresses());
        Settings.SIM_SPOOF.save(true);
        assertEquals("Image server addresses from the feed: 3 lists for 2 hosts, 1 used, 2 set aside so the hosts "
            + "are looked up on the real network", ImageLoads.placement(null).get(1));

        ImageLoads.requested(new ImageLoadsTest.FetchState("https://p16-common-sign.tiktokcdn.com/obj/a.webp"));
        ImageLoads.requested(new ImageLoadsTest.FetchState("https://p16-sg.tiktokcdn.com/obj/b.webp"));
        List<String> lines = ImageLoads.sectionForTests().lines();
        assertTrue(lines.toString(), lines.contains(
            "p16-common-sign.tiktokcdn.com: 1 fetched, 0 failed, the feed named its addresses"));
        assertTrue(lines.toString(), lines.contains("p16-sg.tiktokcdn.com: 1 fetched, 0 failed"));
        assertFalse(lines.toString(), lines.toString().contains("23.0.0"));
    }

    @Test public void theExportSaysHowManyAnswersWentBack() {
        NetworkStackSim.region("US");
        NetworkStackSim.networkOperator("310260");
        assertEquals("SIM spoof: on, US, network stack kept on the real SIM (2 answers)",
            ImageLoads.placement(null).get(0));
        ImageLoads.resetForTests();
        assertEquals("SIM spoof: on, US", ImageLoads.placement(null).get(0));
    }
}
