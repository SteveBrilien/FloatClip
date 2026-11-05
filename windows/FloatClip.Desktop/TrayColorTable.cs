using System.Drawing;
using System.Windows.Forms;

namespace FloatClip.Desktop;

sealed class TrayColorTable(bool dark):ProfessionalColorTable
{
    readonly Color surface=dark?Color.FromArgb(36,40,59):Color.White;
    readonly Color hover=dark?Color.FromArgb(54,62,89):Color.FromArgb(234,241,253);
    readonly Color border=dark?Color.FromArgb(65,72,104):Color.FromArgb(220,225,232);
    public override Color ToolStripDropDownBackground=>surface;
    public override Color ImageMarginGradientBegin=>surface;
    public override Color ImageMarginGradientMiddle=>surface;
    public override Color ImageMarginGradientEnd=>surface;
    public override Color MenuItemSelected=>hover;
    public override Color MenuItemBorder=>hover;
    public override Color MenuItemSelectedGradientBegin=>hover;
    public override Color MenuItemSelectedGradientEnd=>hover;
    public override Color SeparatorDark=>border;
    public override Color SeparatorLight=>surface;
    public override Color ToolStripBorder=>border;
}
