package com.limelight.pocket.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Chip model numbers named the way they're sold. */
public class DeviceInfoPanelTest {

    @Test
    public void chipNames() {
        assertEquals("Snapdragon 8 Gen 2", DeviceInfoPanel.chipName("QTI", "SM8550"));
        assertEquals("Snapdragon 8 Gen 2", DeviceInfoPanel.chipName("QTI", "QCS8550"));
        assertEquals("Snapdragon 865 / 870", DeviceInfoPanel.chipName("QTI", "sm8250"));
        assertEquals("Dimensity 1100", DeviceInfoPanel.chipName("Mediatek", "MT6891"));
        assertEquals("Unisoc T618", DeviceInfoPanel.chipName("Unisoc", "T618"));
        assertEquals("Acme X1", DeviceInfoPanel.chipName("Acme", "X1"));
        assertNull(DeviceInfoPanel.chipName("QTI", ""));
        assertNull(DeviceInfoPanel.chipName("QTI", "unknown"));
        assertNull(DeviceInfoPanel.chipName(null, null));
    }

    @Test
    public void makers() {
        assertTrue(DeviceInfoPanel.isQualcomm("QTI", "qcom"));
        assertTrue(DeviceInfoPanel.isQualcomm(null, "qcom"));
        assertFalse(DeviceInfoPanel.isQualcomm("Mediatek", "mt6891"));
        assertTrue(DeviceInfoPanel.isMediaTek("Mediatek", "mt6891"));
        assertTrue(DeviceInfoPanel.isMediaTek(null, "mt6893"));
        assertFalse(DeviceInfoPanel.isMediaTek("QTI", "qcom"));
    }
}
