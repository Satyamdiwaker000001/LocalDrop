namespace LocalDrop.Transfer;

// M01 boundary: streaming transfer engine arrives M08.
// Full-file RAM buffering is never permitted.
public static class TransferBoundary
{
    public const string Note = "No transfer in M01.";
}
