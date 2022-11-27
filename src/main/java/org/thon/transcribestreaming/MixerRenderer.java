package org.thon.transcribestreaming;

import java.awt.Component;

import javax.sound.sampled.Mixer;
import javax.sound.sampled.Mixer.Info;

import javafx.util.Callback;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;

/**
 * Used to render the dropdown menu for selecting audio input.
 */
public class MixerRenderer implements Callback<ListView<Mixer.Info>, ListCell<Mixer.Info>> {

    @Override
    public ListCell<Info> call(ListView<Info> param) {
        ListCell<Info> cell = new ListCell<Info>() {
            {
                super.setPrefWidth(100);
            }
            @Override
            protected void updateItem(Info item, boolean empty) {
                super.updateItem(item, empty);
                if (item != null) {
                    setText(item.getName());
                } else {
                    setText(null);
                }
            }
        };
        return cell;
    }

}
