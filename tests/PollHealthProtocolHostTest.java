import com.brz.gauge.trips.TripBleProtocol;
import com.brz.gauge.trips.TripRecord;
import com.brz.gauge.trips.PollHealth;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Run against the actual compiled App classes, without an Android device. */
public class PollHealthProtocolHostTest {
    private static final TripBleProtocol protocol = TripBleProtocol.INSTANCE;
    private static byte[] packet(int version, int requested, int received) {
        int size = version == 1 ? 40 : version == 2 ? 48 : 56;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte)version).put((byte)1).putShort((short)size).putInt(42);
        b.putLong(1787882400L).putLong(1787886000L);
        b.putInt(3600).putInt(48210).putInt(4210).putShort((short)873);
        if (version >= 2) b.putShort((short)138).putShort((short)6840).putShort((short)326).putShort((short)-714);
        if (version >= 3) b.putInt(requested).putInt(received);
        b.putShort((short)protocol.crc16(b.array(), size - 2));
        return b.array();
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        for (int version = 1; version <= 3; version++) {
            byte[] packet = packet(version, 0x80002003, 2);
            TripRecord trip = protocol.parseRecord("fixture", packet);
            check(trip != null && trip.getTripId() == 42 && trip.getDistanceM() == 48210);
            check(trip.getHasPollHealth() == (version == 3));
            if (version >= 2) check(trip.getMaxDecelX100() == -714);
            if (version == 3) {
                check(trip.getPollRequested() == 0x80002003L && trip.getPollReceived() == 2);
                check(PollHealth.INSTANCE.status(trip, PollHealth.INSTANCE.getItems().get(0)) == PollHealth.Status.REQUESTED);
                check(PollHealth.INSTANCE.status(trip, PollHealth.INSTANCE.getItems().get(1)) == PollHealth.Status.RECEIVED);
                check(PollHealth.INSTANCE.status(trip, PollHealth.INSTANCE.getItems().get(2)) == PollHealth.Status.NOT_REQUESTED);
            } else {
                check(PollHealth.INSTANCE.status(trip, PollHealth.INSTANCE.getItems().get(1)) == PollHealth.Status.UNRECORDED);
            }
            packet[packet.length - 4] ^= 1;
            check(protocol.parseRecord("fixture", packet) == null); // corruption must not create evidence
            check(protocol.parseRecord("fixture", new byte[packet.length - 1]) == null);
        }
        ByteBuffer meta = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        meta.put((byte)4).put((byte)1).putShort((short)0).putInt(42).putInt(42).putInt(0).putShort((short)64).putShort((short)56);
        check(protocol.parseMeta(meta.array()) != null);
        meta.putShort(18, (short)48);
        check(protocol.parseMeta(meta.array()) == null);
        if (args.length > 0) {
            byte[] wire = Files.readAllBytes(Path.of(args[0]));
            check(wire.length == 208);
            var initial = protocol.parseMeta(Arrays.copyOfRange(wire, 0, 20));
            var next = protocol.parseMeta(Arrays.copyOfRange(wire, 132, 152));
            check(initial != null && initial.getNewestId() == 2 && initial.getPendingCount() == 2);
            check(next != null && next.getNewestId() == 3 && next.getLastAckedId() == 2 && next.getPendingCount() == 1);
            int[] offsets = {20, 76, 152};
            for (int i = 0; i < offsets.length; i++) {
                byte[] packet = Arrays.copyOfRange(wire, offsets[i], offsets[i] + 56);
                TripRecord trip = protocol.parseRecord("firmware-fixture", packet);
                check(trip != null && trip.getTripId() == i + 1);
                check(trip.getDurationS() == 3600 && trip.getDistanceM() == 48210 && trip.getFuelMl() == 4210);
                check(trip.getAvgL100X100() == 873 && trip.getMaxRpm() == 6840 && trip.getMaxDecelX100() == -714);
                check(trip.getPollRequested() == 0x80002003L && trip.getPollReceived() == 2);
                packet[32] ^= 1;
                check(protocol.parseRecord("firmware-fixture", packet) == null);
            }
            System.out.println("PASS: production firmware archives/ACK/new trip -> current App parser");
        }
        if (args.length > 1) {
            var firmware = protocol.parseFirmwareInfo(Files.readAllBytes(Path.of(args[1])));
            check(firmware != null && firmware.getVersion().equals("4.0.4") && firmware.getOtaSlots() == 2);
            System.out.println("PASS: 4.0.4 manifest accepted by current App parser");
        }
        System.out.println("PASS: legacy/new records, CRC rejection, unsigned masks, status labels and metadata");
    }
}
