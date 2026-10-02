import java.nio.file.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import org.traceform.edge.OwnerAuthentication;
public class TraceformOwnerFixture {
  public static void main(String[] args) throws Exception {
    Path directory=Paths.get(args[0]);
    String pin=args[1];
    byte[] inputs=Files.readAllBytes(directory.resolve("public_inputs.bin"));
    byte[] proof=Files.readAllBytes(directory.resolve("proof.bin"));
    KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"));
    PublicKey[] owners=new PublicKey[2];
    for(int owner=0;owner<2;owner++) {
      KeyPair key=generator.generateKeyPair(); owners[owner]=key.getPublic();
      Signature signer=Signature.getInstance("SHA256withECDSA"); signer.initSign(key.getPrivate());
      signer.update("traceform.gw.owner-approval.v1\0".getBytes("UTF-8")); signer.update((byte)owner);
      signer.update(pin.getBytes("UTF-8")); signer.update(inputs); signer.update(proof);
      Files.write(directory.resolve("owner"+owner+".sig"),signer.sign());
      Files.write(directory.resolve("owner"+owner+".der"),key.getPublic().getEncoded());
    }
    OwnerAuthentication.verify(directory.toFile(),pin,owners);
    PublicKey[] replacement=owners.clone(); replacement[1]=generator.generateKeyPair().getPublic();
    boolean rejected=false;
    try { OwnerAuthentication.verify(directory.toFile(),pin,replacement); } catch(SecurityException e) { rejected=true; }
    if(!rejected) throw new AssertionError("replacement owner accepted");
    byte[] changed=inputs.clone(); changed[0]^=1; Files.write(directory.resolve("public_inputs.bin"),changed);
    rejected=false;
    try { OwnerAuthentication.verify(directory.toFile(),pin,owners); } catch(SecurityException e) { rejected=true; }
    Files.write(directory.resolve("public_inputs.bin"),inputs);
    if(!rejected) throw new AssertionError("changed statement accepted");
    System.out.println("{\"owners_verified\":true,\"replacement_owner_rejected\":true,\"modified_statement_rejected\":true,\"private_keys_written\":false}");
  }
}
