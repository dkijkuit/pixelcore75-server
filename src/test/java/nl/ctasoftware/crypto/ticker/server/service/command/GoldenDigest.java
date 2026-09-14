package nl.ctasoftware.crypto.ticker.server.service.command;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The recorded golden RGB565 digests (SHA-256 over the 2048 u16 pixel values, LE — see
 * {@link CommandGolden#digest}). Regenerate with
 * {@code -Dpixelcore75.golden.record=true} and review before committing.
 */
public final class GoldenDigest {

    private static final Map<String, String> GOLDENS = Map.ofEntries(
            Map.entry("aircraft-closest", "e173810bebcaea1934bc10171a7345454e9d988c182f921dc742a8d9c70a8028"),
            Map.entry("aircraft-list", "943797ac5bd016618e18089c0971da1fdddd297687ec0844efdeef0af46832fa"),
            Map.entry("aircraft-radar-t0", "66a3d77e475d131957f5e013242db22467e7164b0997f946cb9e6621cf482422"),
            Map.entry("aircraft-radar-t850", "b4980b663586509aef1964a7c71d41f732bbcc512794513c82591b21bba31faf"),
            Map.entry("aircraft-registry", "d591d7fc0454a183d683476ee64e223defe80614139d6f63c530eb70e0b66729"),
            Map.entry("aircraft-route", "7156f4a3c1f7990cfdca8cad259d7109043efe28b3ceb3707f64234e1507f267"),
            Map.entry("clock", "170f34cc12fd4a6d18aae0fd6c8b8944b8d191ec2ffe56fad7d699fd7ae1e6a1"),
            Map.entry("crypto-dollar", "be5ad6eb27ef5181f7cb6662ddc345abc6779bf3969d03fef0497fbd0719e2d1"),
            Map.entry("crypto-euro", "1fe919ef2db0117f69c070616c2b78eb3becb7fe3f43128e5dcc2ca72acff27f"),
            Map.entry("crypto-pound", "ab08eb43c4b1c9a48eaa81d52011c36a7b75b85205043ce0d6bd6c4419001c05"),
            Map.entry("date", "8d9f1589bf3948abd70bba804d9deb18e8d39efa4cf6dfff9b774be4ef0f8932"),
            Map.entry("f1-calendar", "409c6a95363b56a3f16b57aa6d58da74587f92649f6a2f57399349c3619f2fd7"),
            Map.entry("f1-next-event", "d49141fd57cc97ec5cebacea363a96ced5c3bbbb71d5dce233716c07989d5454"),
            Map.entry("f1-next-session", "118a8d402ad2acada3c0d86de7a259447a2963d35edf131e3e596828cb77cd69"),
            Map.entry("f1-standings", "e83436605bdeb2e0c71b677afed71e98aedb5430977890fe6df11472355ce096"),
            Map.entry("soccer-fulltime", "ae9ff8a223c3d5f907302ade1251d34d98343da2da6eb87067d0e833f2a2f5f7"),
            Map.entry("soccer-live", "832ba29eb470cedf1397ee94535edbb76bbf093509af0f98afda50716600ace4"),
            Map.entry("soccer-nomatch", "e08eaf1a99ba14272046efc8c5d6f7d671d128a03be918c62fdba817dadc35be"),
            Map.entry("soccer-scheduled", "8073bed639a3ca694c47cb94d1864053c948885f8f84819a5e1813c0f1b20fe3"),
            Map.entry("spotify-idle", "3a331a001a1cb23de706eb6a52eb7ed86f479bf1e553647d7b0cf3be6223c676"),
            Map.entry("spotify-noart", "b9a3aa96df88aab4f67ac98fdfba693bf9bb830a07a7408aa60aae726a9ec23a"),
            Map.entry("spotify-notconnected", "39cd50e36b39c3da7e556cfe6260931e62a5f05dd719773076c2a98bb4926641"),
            Map.entry("spotify-paused", "1240ef2232ae502e48a44568c21901f94178e28bec5fc9672de912d2d50c2e53"),
            Map.entry("spotify-playing", "89836ec7ab30d1612ae3e437efa95d2efe6599d852870dc6166191f5b0a6ddda"),
            Map.entry("weather-forecast", "0d7f9a18f6384cce6e8939133661782e60886d759ccfba7ef1515b780414dbde"),
            Map.entry("weather-winter", "134d4354644223831d1986b2d6620d662d1d605a9c9d4863f90cb09efc402687"));

    private GoldenDigest() {
    }

    public static String forName(final String name) {
        final String digest = GOLDENS.get(name);
        if (digest == null) {
            throw new NoSuchElementException(
                    "no golden recorded for '" + name + "' — add it to GoldenDigest (record with -Dpixelcore75.golden.record=true)");
        }
        return digest;
    }
}
