.class public Lcom/xj/winemu/sidebar/BhFsrFragment;
.super Landroidx/fragment/app/Fragment;
.source "SourceFile"

# FSR upscaling sidebar tab. A thin androidx Fragment; the UI and logic live
# in app.revanced.extension.gamehub.BhFsrPanel / BhFsr.

.method public constructor <init>()V
    .locals 0
    invoke-direct {p0}, Landroidx/fragment/app/Fragment;-><init>()V
    return-void
.end method

.method public onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;
    .locals 1
    invoke-virtual {p0}, Landroidx/fragment/app/Fragment;->getActivity()Landroidx/fragment/app/FragmentActivity;
    move-result-object v0
    if-eqz v0, :no_activity
    invoke-static {v0}, Lapp/revanced/extension/gamehub/BhFsrPanel;->create(Landroid/content/Context;)Landroid/view/View;
    move-result-object v0
    return-object v0
    :no_activity
    const/4 v0, 0x0
    return-object v0
.end method

.method public onResume()V
    .locals 0
    invoke-super {p0}, Landroidx/fragment/app/Fragment;->onResume()V
    invoke-static {}, Lapp/revanced/extension/gamehub/BhFsrPanel;->onShown()V
    return-void
.end method

.method public onPause()V
    .locals 0
    invoke-super {p0}, Landroidx/fragment/app/Fragment;->onPause()V
    invoke-static {}, Lapp/revanced/extension/gamehub/BhFsrPanel;->onHidden()V
    return-void
.end method
