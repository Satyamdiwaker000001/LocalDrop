// M14 tray policy tests (headless): menu model, close-vs-exit semantics,
// exit-confirmation matrix, and real-state-only status text. The WinForms
// adapter itself needs an interactive desktop and is NOT RUN here.
using LocalDrop.Infrastructure;
using LocalDrop.Transfer;
using Xunit;

namespace LocalDrop.Protocol.Tests;

public sealed class TrayPolicyTests
{
    [Fact]
    public void Menu_ContainsExactlyTheSpecItemsInOrder()
    {
        var items = TrayPolicy.MenuItems;
        Assert.Equal(4, items.Count);
        Assert.Equal(TrayMenuAction.Open, items[0].Action);
        Assert.Equal("Open LocalDrop", items[0].Label);
        Assert.True(items[0].IsDefault);
        Assert.Equal(TrayMenuAction.ActiveTransfers, items[1].Action);
        Assert.Equal("Active Transfers", items[1].Label);
        Assert.Equal(TrayMenuAction.Send, items[2].Action);
        Assert.Equal("Nearby Devices / Send", items[2].Label);
        Assert.Equal(TrayMenuAction.Exit, items[3].Action);
        Assert.Equal("Exit", items[3].Label);
        Assert.Single(items, i => i.IsDefault);
    }

    [Fact]
    public void WindowClose_AlwaysHidesToTrayUnlessExitRequested()
    {
        Assert.True(TrayPolicy.ShouldHideToTrayOnClose(exitRequested: false));
        Assert.False(TrayPolicy.ShouldHideToTrayOnClose(exitRequested: true));
    }

    [Theory]
    [InlineData(true, true)]
    [InlineData(false, false)]
    public void ExitConfirmation_RequiredOnlyWhileSendBusy(bool busy, bool expected)
    {
        Assert.Equal(expected, TrayPolicy.ConfirmExitNeeded(busy));
    }

    [Fact]
    public void ExitConfirm_TextSaysTransfersAreCancelled()
    {
        Assert.Contains("cancelled", TrayPolicy.ExitConfirmText, StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData(true, SendPhase.Sending, "photo.jpg", "LocalDrop — Sending photo.jpg")]
    [InlineData(true, SendPhase.Sending, "", "LocalDrop — Sending…")]
    [InlineData(true, SendPhase.Sending, "   ", "LocalDrop — Sending…")]
    [InlineData(false, SendPhase.Idle, "", "LocalDrop")]
    [InlineData(false, SendPhase.Succeeded, "photo.jpg", "LocalDrop")]
    public void Tooltip_ReflectsRealSendStateOnly(
        bool busy, SendPhase phase, string file, string expected)
    {
        Assert.Equal(expected, TrayPolicy.TooltipText(busy, phase, file));
    }

    [Theory]
    [InlineData(true, SendPhase.Sending, "photo.jpg", "Sending photo.jpg…")]
    [InlineData(true, SendPhase.Sending, "", "Sending…")]
    [InlineData(false, SendPhase.Succeeded, "", "Last transfer completed.")]
    [InlineData(false, SendPhase.Failed, "", "Last transfer failed.")]
    [InlineData(false, SendPhase.Cancelled, "", "Last transfer cancelled.")]
    [InlineData(false, SendPhase.Idle, "", "Ready. Nearby devices appear automatically.")]
    public void StatusText_ReflectsRealSendStateOnly(
        bool busy, SendPhase phase, string file, string expected)
    {
        Assert.Equal(expected, TrayPolicy.StatusText(busy, phase, file));
    }
}
