# FloatClip project instructions

Before changing Windows UI, read `docs/design/FLOATCLIP_WINDOWS_DESIGN_SYSTEM.md` and the current design change log. These are the single design and acceptance rules for this repository.

- Keep WPF + XAML, existing data formats and native clipboard/pairing/sync services. Do not migrate UI frameworks for visual changes.
- Reuse the existing WPF UI library and shared components. Do not mix its default theme with another framework's default theme or treat WinUI/Web APIs as WPF APIs.
- Use semantic design resources and motion tokens. New page code must not introduce arbitrary color, corner, shadow or animation literals.
- Keep view state and commands in ViewModels, with services owning data and platform operations. Prefer CommunityToolkit.Mvvm.
- Light is neutral Fluent; Dark uses Tokyo Night. Every interactive component needs hover, pressed, focus, disabled and applicable selected/loading/error states.
- Follow the eight-step workflow and nine-area acceptance report in the design document. Report unverified DPI, material, animation and device behavior honestly.
- Preserve unrelated local changes and never include user history, credentials, signing keys or pairing stores in Git.
