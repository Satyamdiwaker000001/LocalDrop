using Microsoft.Extensions.Logging;

namespace LocalDrop.Core;

// M01 Foundation: logging boundary over Microsoft.Extensions.Logging.
// No secrets may be logged (SECURITY v1.0 §5). No cloud/telemetry sink.
public static class AppLogging
{
    public static ILoggerFactory CreateFactory() =>
        LoggerFactory.Create(builder =>
        {
            builder.AddConsole();
            builder.SetMinimumLevel(LogLevel.Information);
        });
}
