import org.bouncycastle.crypto.digests.Blake3Digest;
public class test_bc {
    public static void main(String[] args) {
        Blake3Digest d = new Blake3Digest(256);
        System.out.println("BC has Blake3!");
    }
}
