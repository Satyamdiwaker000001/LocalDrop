using System.Windows;
using LocalDrop.Transfer;

namespace LocalDrop.Presentation;

// M07 UI closure: thin view glue. Every handler delegates to SendController
// (the DataContext); no networking, file I/O, crypto, or orchestration here.
public partial class MainWindow : Window
{
    private SendController Controller => (SendController)DataContext;

    public MainWindow()
    {
        InitializeComponent();
    }

    private void Refresh_Click(object sender, RoutedEventArgs e) => Controller.RefreshPeersNow();

    private void PickFile_Click(object sender, RoutedEventArgs e) => Controller.PickFile();

    private void ClearFile_Click(object sender, RoutedEventArgs e) => Controller.ClearFile();

    private void Send_Click(object sender, RoutedEventArgs e) => Controller.StartSend();

    private void Cancel_Click(object sender, RoutedEventArgs e) => Controller.CancelSend();
}
