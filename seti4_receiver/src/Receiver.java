import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

import static java.util.Arrays.copyOfRange;

/**
 * Created by nikita on 29.11.2017.
 */
public class Receiver {
    int packetSize = 1000;

    public Receiver(int port1, int port2, String path){
        DatagramSocket sct1, sct2;
        System.out.println("Receiver: sk2_dst_port=" + port1 + ", " + "sk3_dst_port=" + port2 + ".");
        int prevSegNum = -1;
        int nextSegNum = 0;
        boolean isTransferComplete = false;

        try{
            sct1 = new DatagramSocket(port1); //in
            sct2 = new DatagramSocket(); //out
            System.out.println("Listening");

            try {
                byte[] in_data = new byte[packetSize];
                DatagramPacket inPacket = new DatagramPacket(in_data,	in_data.length);
                InetAddress dstAddr = InetAddress.getByName("127.0.0.1");

                FileOutputStream fos = null;

                path = ((path.substring(path.length()-1)).equals("/"))? path: path + "/";
                File filePath = new File(path);
                if (!filePath.exists())
                    filePath.mkdir();

                while (!isTransferComplete) {

                    sct1.receive(inPacket);
                    byte[] received_checksum = copyOfRange(in_data, 0, 8); //проверяем целостность данных
                    CRC32 checksum = new CRC32();
                    checksum.update(copyOfRange(in_data, 8, inPacket.getLength()));
                    byte[] calculated_checksum = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();

                    if (Arrays.equals(received_checksum, calculated_checksum)){ //если пришел нормальный сегмент
                        int seqNum = ByteBuffer.wrap(copyOfRange(in_data, 8, 12)).getInt(); //получаем номер сегмента
                        System.out.println("Received sequence number: " + seqNum);

                        if (seqNum == nextSegNum){ //если пришел правильный

                            if (inPacket.getLength() == 12){ //процедура завершения передачи
                                byte[] ackPkt = generatePacket(-2);

                                for (int i=0; i<20; i++)
                                    sct2.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddr, port2));

                                isTransferComplete = true;
                                System.out.println("All packets received! File Created!");
                                continue;
                            }

                            else{
                                byte[] ackPkt = generatePacket(seqNum); //отправляем подтверждение получения
                                sct2.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddr, port2));
                                System.out.println("Receiver: Sent Ack " + seqNum);
                            }


                            if (seqNum==0 && prevSegNum==-1){ //если первый сегмент, то создадим файл
                                int fileNameLength = ByteBuffer.wrap(copyOfRange(in_data, 12, 16)).getInt();
                                String fileName = new String(copyOfRange(in_data, 16, 16 + fileNameLength));
                                System.out.println("Receiver: fileName length: " + fileNameLength + ", fileName:"
                                        + fileName);

                                File file = new File(path + fileName);
                                if (!file.exists()) file.createNewFile();

                                fos = new FileOutputStream(file);

                                fos.write(in_data, 16 + fileNameLength, inPacket.getLength() - 16 - fileNameLength);
                            }

                            else fos.write(in_data, 12, inPacket.getLength() - 12);

                            nextSegNum ++;
                            prevSegNum = seqNum;
                        }

                        else{ // если сегмент дублируется
                            byte[] ackPkt = generatePacket(prevSegNum);
                            sct2.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddr, port2));
                            System.out.println("Sent duplicate Ack " + prevSegNum);
                        }
                    }

                    else{ //если сегмент поврежден
                        System.out.println("Receiver: Corrupt packet dropped");
                        byte[] ackPkt = generatePacket(prevSegNum);
                        sct2.send(new DatagramPacket(ackPkt, ackPkt.length, dstAddr, port2));
                        System.out.println("Sent duplicate Ack " + prevSegNum);
                    }
                }
                if (fos != null) fos.close();
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(-1);
            } finally {
                sct1.close();
                sct2.close();
                System.out.println("Receiver: sk2 closed!");
                System.out.println("Receiver: sk3 closed!");
            }
        } catch (SocketException e1) {
            e1.printStackTrace();
        }
        }


    public byte[] generatePacket(int ackNum){ //формируем пакет с подтверждением
        byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(ackNum).array();
        CRC32 checksum = new CRC32();
        checksum.update(ackNumBytes);

        ByteBuffer pktBuf = ByteBuffer.allocate(12);
        pktBuf.put(ByteBuffer.allocate(8).putLong(checksum.getValue()).array());
        pktBuf.put(ackNumBytes);
        return pktBuf.array();
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: java Receiver sk2_dst_port, sk3_dst_port, outputFolderPath");
            System.exit(-1);
        }
        else new Receiver(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2]);
    }
}
