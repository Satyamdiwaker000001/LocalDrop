// M05: thin UI state models. Security/domain layers own the state machines;
// UI observes snapshots through delegates. No WPF imports, no second
// authorization engine in the UI.
namespace LocalDrop.Security;

public static class AuthUiState
{
    // Receiver display form: "583921" -> "583 921".
    public static string FormatCode(string code) =>
        code.Length == 6 ? code[..3] + " " + code[3..] : code;

    // Sender input normalization: strips spaces/dashes, keeps digits.
    public static string NormalizeInput(string input) =>
        new(input.Where(char.IsDigit).ToArray());
}

public enum ReceiverPhase { IDLE, CODE_ACTIVE, AUTHORIZED, INVALID, EXPIRED, CANCELLED }

public sealed record ReceiverTransactionUiState(
    string? TransactionId,
    string CodeDisplay, // "" when no active code; full code only on the receiver screen
    ReceiverPhase Phase,
    long ExpiresAtMillis,
    string? AuthorizedPeerDeviceId = null);

public enum SenderPhase
{
    IDLE, AUTHENTICATING, AUTHENTICATED, SUBMITTING, AUTHORIZED, FAILED
}

public sealed record SenderAuthUiState(
    string? PeerDeviceId,
    SenderPhase Phase,
    string? LastError = null); // generic text only, never secrets
