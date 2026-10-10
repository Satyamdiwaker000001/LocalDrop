// M05: production security-logging backend over the existing MEL logging
// (no cloud sink). Separated from SecurityLogger.cs so the dependency-free
// contract + test sink compile everywhere; this backend ships in the app.
using Microsoft.Extensions.Logging;
using LocalDrop.Core;

namespace LocalDrop.Security;

// Production sink: delegates to the existing MEL logging.
public sealed class LoggerSecurityLogger(ILogger logger) : ISecurityLogger
{
    public void AuthEvent(bool succeeded, string? peerDeviceId, string detail)
    {
        string line = $"auth ok={succeeded} peer={peerDeviceId ?? "-"} {detail}";
        if (succeeded) logger.LogInformation("{Line}", line);
        else logger.LogWarning("{Line}", line);
    }

    public void TransactionEvent(string type, string transactionId, string? peerDeviceId, string detail) =>
        logger.LogInformation("txn type={Type} id={Id} peer={Peer} {Detail}",
            type, transactionId, peerDeviceId ?? "-", detail);
}

public static class SecurityLoggers
{
    public static ISecurityLogger CreateDefault() =>
        new LoggerSecurityLogger(
            AppLogging.CreateFactory().CreateLogger("LocalDropSec"));
}
