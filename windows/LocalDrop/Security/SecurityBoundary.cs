namespace LocalDrop.Security;

// M01 boundary: explicit pairing/authentication arrives in M05.
// Established platform primitives only; no custom crypto ever.
public static class SecurityBoundary
{
    public const string Note = "No pairing/crypto in M01.";
}
