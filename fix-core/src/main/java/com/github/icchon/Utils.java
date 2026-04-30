package com.github.icchon;

public class Utils {
    public static class ChecksumUtils {
        public static String calculate(String data) {
            int sum = 0;
            byte[] bytes = data.getBytes();
            for (byte b : bytes) {
                sum += b;
            }
            int checksum = sum % 256;
            return String.format("%03d", checksum);
        }
        public static boolean validate(String data, String expectedChecksum) {
            return calculate(data).equals(expectedChecksum);
        }
    }

    private static int _cnt = 1;
    public static String getID(){
        String res =  String.format("%06d", _cnt % 1000000);
        _cnt++;
        return res;
    }
}
