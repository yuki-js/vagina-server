package app.vagina.server.usecase;

public class UnsupportedAuthProviderException extends RuntimeException {

  public UnsupportedAuthProviderException(String provider) {
    super("Unsupported OIDC provider: " + provider);
  }
}
