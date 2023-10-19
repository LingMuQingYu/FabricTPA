package eu.codedsakura.fabrictpa;

import java.lang.reflect.Array;
import java.util.List;

public interface IStoreHome {

    int setHome(WorldCoordinate worldCoordinate, String homeName);

    void setOldWorldCoordinate(WorldCoordinate worldCoordinate);

    WorldCoordinate getOldWorldCoordinate();

    WorldCoordinate getHome(String homeName);

    int delHome(String homeName);

    List<String> getHomeNames();

    List<WorldCoordinate> getOldWorldCoordinates();

}
