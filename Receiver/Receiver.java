import java.net.*;
import java.io.*;
import java.util.HashMap;

public class Receiver {

    public static void main(String[] args) {
        if (args.length != 5) {
            System.out.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            return;
        }

        String senderIP = args[0];
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int rn = Integer.parseInt(args[4]);

        DatagramSocket socket = null;
        FileOutputStream fos = null;

        try {
            InetAddress senderAddress = InetAddress.getByName(senderIP);
            socket = new DatagramSocket(rcvDataPort);
            fos = new FileOutputStream(outputFile);

            System.out.println("Receiver started on port " + rcvDataPort);

            int ackCount = 0;
            int expectedSeq = 1;
            boolean connected = false;
            boolean done = false;
            HashMap<Integer, DSPacket> bufferMap = new HashMap<>();
            int windowSize = 80;

            while (!done) {
                byte[] buffer = new byte[DSPacket.MAX_PACKET_SIZE];
                DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
                socket.receive(udpPacket);

                DSPacket packet = new DSPacket(udpPacket.getData());

                byte type = packet.getType();
                int seq = packet.getSeqNum();
                int length = packet.getLength();

                if (!connected) {
                    if (type == DSPacket.TYPE_SOT && seq == 0) {
                        System.out.println("Received SOT seq=" + seq);

                        ackCount++;
                        sendAck(socket, senderAddress, senderAckPort, seq, ackCount, rn);

                        connected = true;
                        expectedSeq = 1;
                    }
                    continue;
                }

                if (type == DSPacket.TYPE_DATA) {

                    if (seq == expectedSeq) {
                        System.out.println("Received expected DATA seq=" + seq + ", length=" + length);

                        writePacketToFile(packet, fos);
                        expectedSeq = (expectedSeq + 1) % 128;

                        while (bufferMap.containsKey(expectedSeq)) {
                            DSPacket bufferedPacket = bufferMap.remove(expectedSeq);
                            System.out.println("Delivered buffered DATA seq=" + expectedSeq);
                            writePacketToFile(bufferedPacket, fos);
                            expectedSeq = (expectedSeq + 1) % 128;
                        }

                    } else if (isWithinWindow(seq, expectedSeq, windowSize)) {
                        if (!bufferMap.containsKey(seq)) {
                            bufferMap.put(seq, packet);
                            System.out.println("Buffered out-of-order DATA seq=" + seq);
                        } else {
                            System.out.println("Duplicate buffered DATA seq=" + seq);
                        }

                    } else {
                        System.out.println("Ignored DATA seq=" + seq);
                    }

                    int cumulativeAck = (expectedSeq - 1 + 128) % 128;
                    ackCount++;
                    sendAck(socket, senderAddress, senderAckPort, cumulativeAck, ackCount, rn);
                }

                else if (type == DSPacket.TYPE_EOT) {
                    System.out.println("Received EOT seq=" + seq);

                    ackCount++;

                    if (ChaosEngine.shouldDrop(ackCount, rn)) {
                         System.out.println("ACK seq=" + seq + " dropped");
                        } else {
                DSPacket ackPacket = new DSPacket(DSPacket.TYPE_ACK, seq, new byte[0]);
                byte[] ackBytes = ackPacket.toBytes();

                DatagramPacket udpAck = new DatagramPacket(
                ackBytes,
                ackBytes.length,    
                senderAddress,
                senderAckPort
        );

        socket.send(udpAck);
        System.out.println("ACK seq=" + seq + " sent");

        done = true;
    }
}
            }

            System.out.println("File transfer complete.");

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
                if (socket != null) {
                    socket.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void sendAck(DatagramSocket socket, InetAddress senderAddress,
                                int senderAckPort, int seq, int ackCount, int rn) throws IOException {

        DSPacket ackPacket = new DSPacket(DSPacket.TYPE_ACK, seq, new byte[0]);
        byte[] ackBytes = ackPacket.toBytes();

        DatagramPacket udpAck = new DatagramPacket(
                ackBytes,
                ackBytes.length,
                senderAddress,
                senderAckPort
        );

        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("ACK seq=" + seq + " dropped");
        } else {
            socket.send(udpAck);
            System.out.println("ACK seq=" + seq + " sent");
        }
    }

    private static void writePacketToFile(DSPacket packet, FileOutputStream fos) throws IOException {
        byte[] payload = packet.getPayload();
        fos.write(payload, 0, packet.getLength());
    }

    private static boolean isWithinWindow(int seq, int expectedSeq, int windowSize) {
        int diff = (seq - expectedSeq + 128) % 128;
        return diff > 0 && diff < windowSize;
    }
}
