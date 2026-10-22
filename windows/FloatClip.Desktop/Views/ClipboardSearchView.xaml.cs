using System.Windows;
using System.Windows.Controls;
namespace FloatClip.Desktop.Views;
public partial class ClipboardSearchView:UserControl
{
    public ClipboardSearchView(){InitializeComponent();}
    public TextBox InputControl=>Input;
    public TextBlock Hint=>Placeholder;
    void ClearClicked(object sender,RoutedEventArgs e)=>Input.Focus();
}
