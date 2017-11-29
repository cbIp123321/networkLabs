import java.io.File;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import java.util.concurrent.Semaphore;
import java.util.zip.CRC32;
import java.util.Arrays;

import static java.util.Arrays.copyOfRange;

/**
 * Created by nikita on 29.11.2017.
 */
public class Sender {
    static int dataSize = 988; //размер сегмента 1000, из них 8 - CRC, 4 - номер сегмента
    static int timeoutVal = 300;
    static int winSize = 10;
    int base;
    int nextSegNum;
    String path;
    String fileName;
    Vector<byte[]> packetList; //очередь сегментов
    Timer timer;
    Semaphore s;
    boolean isTransferComplete;


    public void setTimer(boolean isNewTimer){
        if(timer != null)
            timer.cancel();
        if(isNewTimer){
            timer = new Timer();
            timer.schedule(new Timeout(), timeoutVal);
        }
    }

    public class Timeout extends TimerTask {
        public void run(){
            try{
                s.acquire();
                System.out.println("Timeout!");
                nextSegNum = base;
                s.release();
            } catch(InterruptedException e){
                e.printStackTrace();
            }
        }
    }

    public class OutThread extends Thread {
        private DatagramSocket sct;
        private int dstPort;
        private int recvPort;
        private InetAddress dstAddr;

        public OutThread(DatagramSocket sct, int dstPort, int recvPort){
            this.dstPort = dstPort;
            this.recvPort = recvPort;
            this.sct = sct;
        }

        public byte[] generatePacket(int seqNum, byte[] dataBytes){
            byte[] seqNumBytes = ByteBuffer.allocate(4).putInt(seqNum).array();
            CRC32 checksum = new CRC32();
            checksum.update(seqNumBytes);
            checksum.update(dataBytes);
            byte[] checksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();

            ByteBuffer pktBuf = ByteBuffer.allocate(8 + 4 + dataBytes.length);
            pktBuf.put(checksumBytes);
            pktBuf.put(seqNumBytes);
            pktBuf.put(dataBytes);
            return pktBuf.array();
        }

        public void run(){
            try (FileInputStream fis = new FileInputStream(new File(path));
            ){
                dstAddr = InetAddress.getByName("127.0.0.1");
                try{
                    while(!isTransferComplete){
                        if(nextSegNum < base + winSize){ //если окно не заполнено, отправляем сегменты
                            s.acquire();
                            if(base == nextSegNum){ //если первый сегмент в окне, ставим таймер
                                setTimer(true);
                            }
                            byte[] outData = new byte[10];
                            boolean isFinalSeqNum = false;
                            if(nextSegNum < packetList.size()){ //если сегмент есть в packetList, берем его оттуда
                                outData = packetList.get(nextSegNum);
                            }
                            else {
                                if(nextSegNum == 0){ //если сегмент первый, то нужно подготовить информацтию о файле
                                    byte[] fileNameBytes = fileName.getBytes();
                                    byte[] fileNameLengthBytes = ByteBuffer.allocate(4).putInt
                                            (fileNameBytes.length).array();
                                    byte[] dataBuffer = new byte[dataSize];
                                    int dataLength = fis.read(dataBuffer, 0, dataSize - 4 - fileNameBytes.length);
                                    byte[] dataBytes = copyOfRange(dataBuffer, 0, dataLength);
                                    ByteBuffer BB = ByteBuffer.allocate(4 + fileNameBytes.length + dataBytes.length);
                                    BB.put(fileNameLengthBytes);
                                    BB.put(fileNameBytes);
                                    BB.put(dataBytes);
                                    outData = generatePacket(nextSegNum, BB.array());
                                }
                                else {
                                    byte[] dataBuffer = new byte[dataSize];
                                    int dataLength = fis.read(dataBuffer, 0, dataSize);
                                    if(dataLength == -1){ //файл кончился
                                        isFinalSeqNum = true;
                                        outData = generatePacket(nextSegNum, new byte[0]);
                                    }
                                    else {
                                        byte[] dataBytes = copyOfRange(dataBuffer, 0, dataLength);
                                        outData = generatePacket(nextSegNum, dataBytes);
                                    }
                                }
                            }
                            packetList.add(outData);
                            sct.send(new DatagramPacket(outData, outData.length, dstAddr, dstPort));
                            System.out.println("Sent seqNum " + nextSegNum);
                            if(!isFinalSeqNum)
                                nextSegNum++;
                            s.release();
                        }
                        sleep(5);
                    }
                }
                catch (Exception e){
                    e.printStackTrace();
                }
                finally {
                    setTimer(false);
                    sct.close();
                    System.out.println("Socket closed!");
                }
            }
            catch(Exception e){
                e.printStackTrace();
            }
        }
    }

    public class InThread extends Thread {
        DatagramSocket sctIn;

        public InThread(DatagramSocket sctIn) {
            this.sctIn = sctIn;
        }

        int decodePacket(byte[] pkt){
            byte[] receivedChecksumBytes = copyOfRange(pkt, 0, 8); //проверяем целостность, возвращаем номер подтверждения
            byte[] ackNumBytes = copyOfRange(pkt, 8, 12);
            CRC32 checksum = new CRC32();
            checksum.update(ackNumBytes);
            byte[] calculatedChecksumBytes = ByteBuffer.allocate(8).putLong(checksum.getValue()).array();
            if (Arrays.equals(receivedChecksumBytes, calculatedChecksumBytes))
                return ByteBuffer.wrap(ackNumBytes).getInt();
            else return -1;
        }

        public void run() {
            try {
                byte[] inData = new byte[12];
                DatagramPacket inPacket = new DatagramPacket(inData,inData.length);
                try {
                    while (!isTransferComplete) {

                        sctIn.receive(inPacket); //принимаем подтверждение
                        int ackNum = decodePacket(inData);
                        System.out.println("Received Ack " + ackNum);

                        if (ackNum != -1){ //если подтверждение не повреждено
                            if (base == ackNum + 1){ // если предыдущий сегмент не дошел
                                s.acquire();
                                setTimer(false);
                                nextSegNum = base;
                                s.release();
                            }
                            else if (ackNum == -2) //все данные успешно приняты
                                isTransferComplete = true;
                            else{ //если нормальное подтверждение
                                base = ackNum++;
                                s.acquire();
                                if (base == nextSegNum)
                                    setTimer(false);
                                else
                                    setTimer(true);
                                s.release();
                            }
                        }

                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    sctIn.close();
                    System.out.println("socket IN closed!");
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(-1);
            }
        }
    }

    public Sender(int port1, int port2, String path, String fileName){
        base = 0;
        nextSegNum = 0;
        this.path = path;
        this.fileName = fileName;
        packetList = new Vector<>(winSize);
        isTransferComplete = false;
        DatagramSocket sct1, sct2;

        s = new Semaphore(1);

        try{
            sct1 = new DatagramSocket(); //out
            sct2 = new DatagramSocket(port2); //in

            InThread thIn = new InThread(sct2);
            OutThread thOut = new OutThread(sct1, port1, port2);
            thIn.start();
            thOut.start();

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }


    public static void main(String[] args) {
        // parse parameters
        if (args.length != 4) {
            System.err.println("Usage: java Sender sk1_dst_port, sk4_dst_port, inputFilePath, outputFileName");
            System.exit(-1);
        }
        else new Sender(Integer.parseInt(args[0]), Integer.parseInt(args[1]), args[2], args[3]);
    }

}
