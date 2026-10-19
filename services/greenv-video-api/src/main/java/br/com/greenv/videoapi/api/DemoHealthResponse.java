package br.com.greenv.videoapi.api;

/**
 * Whether the demo intake is up, and whether the pairing code the phone holds is the right one.
 * The phone calls this before it offers to record, so a wrong code is caught before a visitor
 * films anything.
 */
public record DemoHealthResponse(String service, int contract, boolean authorised) {

    public static final String SERVICE = "greenv-demo-intake";
    public static final int CONTRACT = 1;

    public static DemoHealthResponse of(boolean authorised) {
        return new DemoHealthResponse(SERVICE, CONTRACT, authorised);
    }
}
