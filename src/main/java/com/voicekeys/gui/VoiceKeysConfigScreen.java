package com.voicekeys.gui;

import com.voicekeys.VoiceKeysMod;
import com.voicekeys.config.VoiceKeysConfig;
import com.voicekeys.voice.VoiceRecognizer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Écran de configuration en jeu : choix du micro, réglages, et édition des
 * associations mot → touche (ajout / suppression / modification). Les changements
 * sont appliqués à chaud au clic sur « Enregistrer ».
 */
public class VoiceKeysConfigScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int TOP_BINDINGS = 174;

    private final Screen parent;
    /** Copie de travail : annulée si on ferme sans enregistrer. */
    private final VoiceKeysConfig work;
    private final List<VoiceKeysConfig.Binding> bindings;

    private int page = 0;
    private int rowsPerPage = 5;

    public VoiceKeysConfigScreen(Screen parent, VoiceKeysConfig current) {
        super(Component.literal("VoiceKeys — Configuration"));
        this.parent = parent;
        this.work = current.copy();
        this.bindings = work.bindings;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        // Calcule combien de lignes de bindings tiennent à l'écran.
        int bottomReserved = 70; // place pour "Ajouter", pagination et Enregistrer/Annuler
        rowsPerPage = Math.max(1, (this.height - bottomReserved - TOP_BINDINGS) / ROW_HEIGHT);
        int pageCount = Math.max(1, (int) Math.ceil(bindings.size() / (double) rowsPerPage));
        if (page >= pageCount) {
            page = pageCount - 1;
        }
        if (page < 0) {
            page = 0;
        }

        // --- Écoute marche/arrêt (en direct, pas besoin d'enregistrer) ---
        VoiceKeysMod mod = VoiceKeysMod.getInstance();
        boolean listeningNow = mod != null && mod.isListening();
        addRenderableWidget(CycleButton.onOffBuilder(listeningNow)
                .create(cx - 150, 26, 300, 20, Component.literal("Écoute (micro)"),
                        (b, v) -> {
                            if (mod != null) {
                                mod.setListening(v);
                            }
                        }));

        // --- Micro ---
        List<String> mics = new ArrayList<>();
        mics.add(""); // défaut système
        mics.addAll(VoiceRecognizer.availableMics());
        if (work.micName != null && !work.micName.isEmpty() && !mics.contains(work.micName)) {
            mics.add(work.micName);
        }
        String micInitial = work.micName == null ? "" : work.micName;
        addRenderableWidget(CycleButton.<String>builder(
                        v -> Component.literal(v.isEmpty() ? "Défaut système" : trim(v, 36)), micInitial)
                .withValues(mics)
                .create(cx - 150, 50, 300, 20, Component.literal("Micro"),
                        (b, v) -> work.micName = v));

        // --- Réglages on/off ---
        addRenderableWidget(CycleButton.onOffBuilder(work.enabledOnStart)
                .create(cx - 152, 74, 150, 20, Component.literal("Écoute au démarrage"),
                        (b, v) -> work.enabledOnStart = v));
        addRenderableWidget(CycleButton.onOffBuilder(work.triggerOnPartial)
                .create(cx + 2, 74, 150, 20, Component.literal("Réaction rapide"),
                        (b, v) -> {
                            work.triggerOnPartial = v;
                            refresh(); // réactualise le grisage Confiance / Stabilité
                        }));

        // --- Confiance + cooldown ---
        List<Double> confValues = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            confValues.add(i / 20.0); // 0.00 .. 1.00 pas de 0.05
        }
        double confInit = snapDouble(work.minConfidence, confValues);
        CycleButton<Double> confButton = CycleButton.<Double>builder(
                        d -> Component.literal(String.format(Locale.ROOT, "%.2f", d)), confInit)
                .withValues(confValues)
                .create(cx - 152, 98, 150, 20, Component.literal("Confiance min"),
                        (b, v) -> work.minConfidence = v);
        // La confiance ne s'applique pas aux résultats partiels : grisée en réaction rapide.
        confButton.active = !work.triggerOnPartial;
        addRenderableWidget(confButton);

        List<Integer> cdValues = List.of(0, 100, 150, 200, 300, 500, 750, 1000, 1500);
        int cdInit = snapInt((int) work.cooldownMs, cdValues);
        addRenderableWidget(CycleButton.<Integer>builder(i -> Component.literal(i + " ms"), cdInit)
                .withValues(cdValues)
                .create(cx + 2, 98, 150, 20, Component.literal("Délai même mot"),
                        (b, v) -> work.cooldownMs = v));

        // --- Push-to-talk + Stabilité (réaction rapide) ---
        addRenderableWidget(CycleButton.onOffBuilder(work.pushToTalk)
                .create(cx - 152, 122, 150, 20,
                        Component.literal("Push-to-talk"),
                        (b, v) -> work.pushToTalk = v));

        int stabInit = Math.min(3, Math.max(1, work.partialConfirmations));
        CycleButton<Integer> stabButton = CycleButton.<Integer>builder(i -> Component.literal(
                        i == 1 ? "Rapide" : i == 2 ? "Équilibré" : "Sûr"), stabInit)
                .withValues(List.of(1, 2, 3))
                .create(cx + 2, 122, 150, 20, Component.literal("Stabilité"),
                        (b, v) -> work.partialConfirmations = v);
        // La stabilité ne sert qu'en réaction rapide : grisée sinon.
        stabButton.active = work.triggerOnPartial;
        addRenderableWidget(stabButton);

        // --- Lignes de bindings (page courante) ---
        int start = page * rowsPerPage;
        int end = Math.min(bindings.size(), start + rowsPerPage);
        for (int i = start; i < end; i++) {
            final VoiceKeysConfig.Binding b = bindings.get(i);
            int y = TOP_BINDINGS + (i - start) * ROW_HEIGHT;

            EditBox wordBox = new EditBox(this.font, cx - 150, y, 96, 20, Component.literal("mot"));
            wordBox.setMaxLength(48);
            wordBox.setValue(b.word);
            wordBox.setHint(Component.literal("mot"));
            wordBox.setResponder(s -> b.word = s.trim().toLowerCase(Locale.ROOT));
            addRenderableWidget(wordBox);

            EditBox keyBox = new EditBox(this.font, cx - 48, y, 160, 20, Component.literal("touche"));
            keyBox.setMaxLength(128);
            keyBox.setValue(b.keybind);
            keyBox.setHint(Component.literal("nom de la touche (options.txt)"));
            keyBox.setResponder(s -> b.keybind = s.trim());
            addRenderableWidget(keyBox);

            final int index = i;
            addRenderableWidget(Button.builder(Component.literal("✕"), btn -> {
                bindings.remove(index);
                refresh();
            }).bounds(cx + 118, y, 20, 20).build());
        }

        // --- Ajouter + pagination ---
        int actionsY = TOP_BINDINGS + rowsPerPage * ROW_HEIGHT + 4;
        addRenderableWidget(Button.builder(Component.literal("+ Ajouter une association"), btn -> {
            bindings.add(new VoiceKeysConfig.Binding("", ""));
            page = (bindings.size() - 1) / rowsPerPage;
            refresh();
        }).bounds(cx - 150, actionsY, 190, 20).build());

        if (bindings.size() > rowsPerPage) {
            addRenderableWidget(Button.builder(Component.literal("◀"), btn -> {
                if (page > 0) {
                    page--;
                    refresh();
                }
            }).bounds(cx + 44, actionsY, 44, 20).build());
            addRenderableWidget(Button.builder(Component.literal("▶"), btn -> {
                if ((page + 1) * rowsPerPage < bindings.size()) {
                    page++;
                    refresh();
                }
            }).bounds(cx + 94, actionsY, 44, 20).build());
        }

        // --- Enregistrer / Annuler ---
        int bottomY = this.height - 28;
        addRenderableWidget(Button.builder(Component.literal("Enregistrer"), btn -> save())
                .bounds(cx - 152, bottomY, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Annuler"), btn -> onClose())
                .bounds(cx + 2, bottomY, 150, 20).build());
    }

    private void save() {
        // Supprime les lignes incomplètes (mot ou touche vide).
        bindings.removeIf(b -> b.word == null || b.word.isBlank()
                || b.keybind == null || b.keybind.isBlank());
        VoiceKeysMod mod = VoiceKeysMod.getInstance();
        if (mod != null) {
            mod.applyConfig(work);
        }
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;

        g.drawCenteredString(this.font, this.title, cx, 10, 0xFFFFFF);
        if (work.pushToTalk) {
            g.drawString(this.font,
                    Component.literal("Touche push-to-talk à définir dans Options > Commandes > VoiceKeys"),
                    cx - 150, 148, 0x808080);
        }
        g.drawString(this.font, Component.literal("Associations mot → touche"), cx - 150, 160, 0xA0A0A0);

        if (bindings.isEmpty()) {
            g.drawCenteredString(this.font,
                    Component.literal("Aucune association. Cliquez sur « Ajouter »."),
                    cx, TOP_BINDINGS + 6, 0x808080);
        }

        if (bindings.size() > rowsPerPage) {
            int pageCount = (int) Math.ceil(bindings.size() / (double) rowsPerPage);
            int actionsY = TOP_BINDINGS + rowsPerPage * ROW_HEIGHT + 4;
            g.drawString(this.font,
                    Component.literal("Page " + (page + 1) + "/" + pageCount),
                    cx + 142, actionsY + 6, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    // ---- utilitaires ----

    private void refresh() {
        this.clearWidgets();
        this.init();
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static double snapDouble(double value, List<Double> allowed) {
        double best = allowed.get(0);
        double bestDiff = Math.abs(value - best);
        for (double v : allowed) {
            double d = Math.abs(value - v);
            if (d < bestDiff) {
                bestDiff = d;
                best = v;
            }
        }
        return best;
    }

    private static int snapInt(int value, List<Integer> allowed) {
        int best = allowed.get(0);
        int bestDiff = Math.abs(value - best);
        for (int v : allowed) {
            int d = Math.abs(value - v);
            if (d < bestDiff) {
                bestDiff = d;
                best = v;
            }
        }
        return best;
    }
}
