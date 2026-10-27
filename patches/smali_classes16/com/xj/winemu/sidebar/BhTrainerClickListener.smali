.class public final Lcom/xj/winemu/sidebar/BhTrainerClickListener;
.super Ljava/lang/Object;
.source "SourceFile"

# Sidebar tab click listener for the Trainer tab.
# Implements Function0 (Kotlin lambda) — same pattern as BhTaskClickListener.

# interfaces
.implements Lkotlin/jvm/functions/Function0;


# instance fields
.field public final a:Lcom/xj/winemu/sidebar/WineActivityDrawerContent;


# direct methods
.method public constructor <init>(Lcom/xj/winemu/sidebar/WineActivityDrawerContent;)V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p1, p0, Lcom/xj/winemu/sidebar/BhTrainerClickListener;->a:Lcom/xj/winemu/sidebar/WineActivityDrawerContent;

    return-void
.end method


# virtual methods
.method public final invoke()Ljava/lang/Object;
    .locals 1

    iget-object p0, p0, Lcom/xj/winemu/sidebar/BhTrainerClickListener;->a:Lcom/xj/winemu/sidebar/WineActivityDrawerContent;

    const-string v0, "BhTrainerFragment"

    invoke-virtual {p0, v0}, Lcom/xj/winemu/sidebar/WineActivityDrawerContent;->U(Ljava/lang/String;)V

    sget-object p0, Lkotlin/Unit;->a:Lkotlin/Unit;

    return-object p0
.end method
