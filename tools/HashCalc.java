public class HashCalc {
  public static void main(String[] a){
    String url="https://download.samplelib.com/mp4/sample-5s.mp4";
    System.out.println(Integer.toHexString(url.hashCode()));
  }
}
