package com.sensirion.smartgadget.view.history;

import android.content.Context;
import android.graphics.Paint;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.View;

import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.XYPlot;
import com.androidplot.xy.XYStepMode;
import com.sensirion.smartgadget.R;
import com.sensirion.smartgadget.utils.Converter;
import com.sensirion.smartgadget.utils.Settings;
import com.sensirion.smartgadget.utils.XmlFloatExtractor;
import com.sensirion.smartgadget.utils.view.ColorManager;
import com.sensirion.smartgadget.view.history.graph.plot_formatter.LinearPlotFormatter;
import com.sensirion.smartgadget.view.history.graph.value_formatter.ShowNothingFormat;
import com.sensirion.smartgadget.view.history.type.HistoryIntervalType;
import com.sensirion.smartgadget.view.history.type.HistoryUnitType;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class PlotHandler {

    private static final String TAG = PlotHandler.class.getSimpleName();

    private static final int THRESHOLD_TIME_REPRESENTATION_MS = 15000; //15 seconds

    private static final int GAP_THRESHOLD_RESOLUTION_MULTIPLIER = 5;

    //Plot Attributes.
    @NonNull
    private final XYPlot mViewPlot;
    @NonNull
    private List<SimpleXYSeries> mDeviceSeries = Collections.synchronizedList(new LinkedList<SimpleXYSeries>());

    private boolean mShouldResetRangeBoundaries = true;
    private double mRangeValueMin;
    private double mRangeValueMax;

    private boolean mIsFahrenheit;

    //Type of data displayed.
    private HistoryUnitType mLastUnit;
    private HistoryIntervalType mLastInterval;

    public PlotHandler(@NonNull final Context context, @NonNull final View historyView, @NonNull final HistoryIntervalType defaultInterval, @NonNull final HistoryUnitType defaultUnitType) {
        mViewPlot = (XYPlot) historyView.findViewById(R.id.history_plot_temperature);
        mViewPlot.setDomainStep(XYStepMode.SUBDIVIDE, defaultInterval.getNumberDomainElements());
        mIsFahrenheit = Settings.getInstance().isTemperatureUnitFahrenheit(context);

        mLastInterval = defaultInterval;
        mLastUnit = defaultUnitType;

        final String defaultDomainText = context.getResources().getString(R.string.graph_label_elapsed_time);
        final String defaultRangeText = context.getResources().getString(R.string.graph_label_temperature);

        LinearPlotFormatter.formatPlot(context, mViewPlot, defaultDomainText, defaultRangeText, new ShowNothingFormat(), new ShowNothingFormat());
    }

    public synchronized void updateSeries(@NonNull final Context context, @NonNull final List<SimpleXYSeries> series, @NonNull final HistoryIntervalType interval, @NonNull final HistoryUnitType type) {
        mShouldResetRangeBoundaries = true;
        mDeviceSeries = series;
        cleanSeries();
        updatePlotRangeFormat(context, type);
        updatePlotDomainFormat(context, interval);
        updatePlot(context);
        Log.i(TAG, "updateSeries -> Series where updated, graph was updated.");
    }

    private void updatePlotDomainFormat(@NonNull final Context context, @NonNull final HistoryIntervalType interval) {
        mLastInterval = interval;
        mViewPlot.setDomainStep(XYStepMode.SUBDIVIDE, mLastInterval.getNumberDomainElements());
        mViewPlot.setDomainValueFormat(mLastInterval.getTimeFormat());
        mViewPlot.setDomainLabel(mLastInterval.getGraphLabel(context));
    }

    private void updatePlotRangeFormat(@NonNull final Context context, @NonNull final HistoryUnitType type) {
        mLastUnit = type;
        mViewPlot.setRangeValueFormat(type.getValueFormat(context));
        mViewPlot.setRangeStep(XYStepMode.SUBDIVIDE, context.getResources().getInteger(R.integer.history_graph_view_num_default_range_labels));
        if (type == HistoryUnitType.TEMPERATURE) {
            updateRangeFormatToTemperature(context);
            Log.i(TAG, "updatePlotRangeFormat -> Updated graph for temperature.");
        } else {
            updateRangeFormatToHumidity(context);
            Log.i(TAG, "updatePlotRangeFormat -> Updated graph for humidity.");
        }
    }

    private void updateRangeFormatToTemperature(@NonNull final Context context) {
        if (Settings.getInstance().isTemperatureUnitFahrenheit(context)) {
            mViewPlot.setRangeLabel(context.getString(R.string.graph_label_temperature_fahrenheit));
            mIsFahrenheit = true;
            Log.i(TAG, "updateRangeFormatToTemperature(): Updated range format to Fahrenheit.");
        } else {
            mViewPlot.setRangeLabel(context.getString(R.string.graph_label_temperature_celsius));
            mIsFahrenheit = false;
            Log.i(TAG, "updateRangeFormatToTemperature(): Updated range format to Celsius.");
        }
    }

    private void updateRangeFormatToHumidity(@NonNull final Context context) {
        //The white space solves a bug in the graph
        final String fixedHumidityString = " " + context.getString(R.string.graph_label_relative_humidity);
        mViewPlot.setRangeLabel(fixedHumidityString);
    }

    private void updatePlot(@NonNull final Context context) {
        cleanSeries();
        boolean validSeriesFound = false;
        long biggestTimestampSeries = 0;
        for (final SimpleXYSeries deviceSeries : mDeviceSeries) {
            checkSeriesRange(deviceSeries);
            final long biggestSeriesTimestamp = obtainBiggestTimestampSeries(deviceSeries);
            if (biggestSeriesTimestamp > biggestTimestampSeries) {
                biggestTimestampSeries = biggestSeriesTimestamp;
            }
            final LineAndPointFormatter deviceFormatter = getDeviceFormatterFromSeries(deviceSeries);
            for (final SimpleXYSeries series : handleGapsForSeries(deviceSeries)) {
                mViewPlot.addSeries(prepareSeriesToShow(series), deviceFormatter);
            }
            validSeriesFound = true;
        }
        adjustGraphFormat(context, biggestTimestampSeries, validSeriesFound);
    }

    private void adjustGraphFormat(@NonNull final Context context, final long biggestTimestampSeries, final boolean validSeriesFound) {
        if (validSeriesFound) {
            adjustGraphBoundaries(context, biggestTimestampSeries);
        } else {
            mViewPlot.setRangeValueFormat(new ShowNothingFormat());
            mViewPlot.setDomainValueFormat(new ShowNothingFormat());
            mViewPlot.setRangeLabel(context.getResources().getString(R.string.graph_label_temperature));
            mViewPlot.redraw();
        }
    }

    /**
     * Separates a series in several series so the graph will have separated lines for very separated datapoints.
     *
     * @param deviceSeries that is going to be slitted.
     * @return {@link java.util.List} with the different series.
     */
    @NonNull
    private List<SimpleXYSeries> handleGapsForSeries(@NonNull final SimpleXYSeries deviceSeries) {
        final List<SimpleXYSeries> listOfSeriesOfDevice = new LinkedList<>();

        SimpleXYSeries analysedSeries = new SimpleXYSeries(deviceSeries.getTitle());

        final long maximumTimeSeparationBetweenGraphPoints = mLastInterval.getIntervalView().getResolution() * GAP_THRESHOLD_RESOLUTION_MULTIPLIER;

        for (int i = deviceSeries.size() - 1; i >= 0; i--) {
            if (analysedSeries.size() > 0) {
                final long timestampDeviceSeries = deviceSeries.getX(i).longValue();
                final long lastTimestampSeriesToShow = analysedSeries.getX(analysedSeries.size() - 1).longValue();
                if (timestampDeviceSeries > lastTimestampSeriesToShow + maximumTimeSeparationBetweenGraphPoints) {
                    listOfSeriesOfDevice.add(analysedSeries);
                    analysedSeries = new SimpleXYSeries(deviceSeries.getTitle());
                }
            }
            analysedSeries.addLast(deviceSeries.getX(i), deviceSeries.getY(i));
        }
        listOfSeriesOfDevice.add(analysedSeries);
        return listOfSeriesOfDevice;
    }

    @NonNull
    private SimpleXYSeries prepareSeriesToShow(@NonNull final SimpleXYSeries series) {
        final SimpleXYSeries fixedDeviceSeries;
        if (mIsFahrenheit && mLastUnit == HistoryUnitType.TEMPERATURE) {
            fixedDeviceSeries = convertSeriesToFahrenheit(series);
        } else {
            fixedDeviceSeries = series;
        }
        if (fixedDeviceSeries.size() == 1) {
            prepare1ValueSeries(fixedDeviceSeries);
        }
        return fixedDeviceSeries;
    }

    @NonNull
    private SimpleXYSeries convertSeriesToFahrenheit(@NonNull final SimpleXYSeries seriesInCelsius) {
        final SimpleXYSeries seriesInFahrenheit = new SimpleXYSeries(seriesInCelsius.getTitle());
        for (int i = 0; i < seriesInCelsius.size(); i++) {
            final Number x = seriesInCelsius.getX(i);
            final Number y = Converter.convertToF(seriesInCelsius.getY(i).floatValue());
            seriesInFahrenheit.addFirst(x, y);
        }
        return seriesInFahrenheit;
    }

    private long obtainBiggestTimestampSeries(@NonNull final SimpleXYSeries series) {
        final long firstValue = series.getX(0).longValue();
        final long lastValue = series.getX(series.size() - 1).longValue();

        if (firstValue > lastValue) {
            return firstValue;
        }
        return lastValue;
    }

    /**
     * Checks if the graph range is modified in some way when.
     *
     * @param series that has to be checked.
     */
    private void checkSeriesRange(@NonNull final SimpleXYSeries series) {
        for (int i = 0; i < series.size(); i++) {
            final float rangeValue = series.getY(i).floatValue();
            if (mIsFahrenheit && mLastUnit == HistoryUnitType.TEMPERATURE) {
                final float rangeValueFahrenheit = Converter.convertToF(rangeValue);
                recalculateRangeBoundaries(rangeValueFahrenheit);
            } else {
                recalculateRangeBoundaries(rangeValue);
            }
        }
    }

    /**
     * By definition a linear format only shows the deference between two values. In this way when we have
     * 1 single value the graph is shown. It extends the value to show a tiny line to display the value.
     *
     * @param series that is going to be prepared to be shown.
     */
    private void prepare1ValueSeries(@NonNull final SimpleXYSeries series) {
        final long resolutionTime = mLastInterval.getIntervalView().getResolution();
        final long displayedLineLength = resolutionTime / 3;
        series.addFirst((series.getX(0).longValue() - displayedLineLength), series.getY(0));
    }

    /**
     * Obtains and prepares the formatter of a series, using the specific color of a device.
     *
     * @param deviceSeries that wants to be formatted.
     * @return {@link com.androidplot.xy.LineAndPointFormatter} of a device series.
     * NOTE: The name of a series MUST be the device address of the device.
     */
    @Nullable
    private LineAndPointFormatter getDeviceFormatterFromSeries(@NonNull final SimpleXYSeries deviceSeries) {
        final int lineColor = ColorManager.getInstance().getDeviceColor(deviceSeries.getTitle());
        final Paint fillColor = new Paint();
        fillColor.setColor(lineColor);
        fillColor.setAlpha(45);
        return new LineAndPointFormatter(lineColor, null, fillColor.getColor(), null);
    }

    /**
     * Deletes all the graph data displayed in the plot.
     */
    public void cleanSeries() {
        for (final SimpleXYSeries oldSeries : mDeviceSeries) {
            mViewPlot.removeSeries(oldSeries);
        }
        mViewPlot.clear();
    }

    /**
     * Constantly recalculates the range boundaries
     *
     * @param rangeValue the current range value
     * @return true if upper and/or lower range boundary has been changed
     */
    private boolean recalculateRangeBoundaries(final double rangeValue) {
        if (mShouldResetRangeBoundaries) {
            Log.i(TAG, String.format("recalculateRangeBoundaries() -> Resetting graph using reference -> %s.", rangeValue));
            mRangeValueMax = rangeValue;
            mRangeValueMin = rangeValue;
            mShouldResetRangeBoundaries = false;
        } else if (mRangeValueMax < rangeValue) {
            Log.i(TAG, String.format("recalculateRangeBoundaries() -> New Maximum Value: %f.", rangeValue));
            mRangeValueMax = rangeValue;
        } else if (mRangeValueMin > rangeValue) {
            Log.i(TAG, String.format("recalculateRangeBoundaries() -> New Minimum Value: %f.", rangeValue));
            mRangeValueMin = rangeValue;
        } else {
            return false;
        }
        return true;
    }

    private void adjustGraphBoundaries(@NonNull final Context context, final long timestampMillis) {
        adjustRangeBoundaries(context);
        adjustDomainBoundaries(timestampMillis);
        mViewPlot.redraw();
    }

    private void adjustRangeBoundaries(@NonNull final Context context) {
        final double boundaryStep = getStep(context);

        double minRangeValue = getMinStep(context, boundaryStep);
        double maxRangeValue = getMaxStep(context, boundaryStep);

        mViewPlot.setRangeLowerBoundary(minRangeValue, BoundaryMode.FIXED);
        mViewPlot.setRangeUpperBoundary(maxRangeValue, BoundaryMode.FIXED);
    }

    private double getStep(@NonNull final Context context) {
        final float graphMargin = XmlFloatExtractor.getFloatValueFromId(context, R.dimen.history_graph_top_bottom_margin);

        final double minimumStep = getMinimumBoundariesStep(context);
        double boundaryStep = (mRangeValueMax - mRangeValueMin) * (1 - graphMargin);
        if (boundaryStep == 0) {
            boundaryStep = (mRangeValueMax < 0) ? mRangeValueMax * 0.001 : mRangeValueMax * 1.001;
        }

        if (boundaryStep < minimumStep) {
            return minimumStep;
        }
        return boundaryStep;
    }

    private double getMinStep(@NonNull final Context context, final double boundaryStep) {
        double minRangeValue = mRangeValueMin - boundaryStep;

        if (mLastUnit == HistoryUnitType.HUMIDITY) {

            final int minHumidityValue = context.getResources().getInteger(R.integer.history_graph_min_humidity_range);
            final int maxHumidityValue = context.getResources().getInteger(R.integer.history_graph_max_humidity_range);
            final int minSeparation = context.getResources().getInteger(R.integer.history_graph_min_separation);

            if (minRangeValue < minHumidityValue) {
                return minHumidityValue;
            }
            if (minRangeValue > maxHumidityValue - minSeparation) {
                return maxHumidityValue - minSeparation;
            }
        }
        return minRangeValue;
    }

    private double getMaxStep(@NonNull final Context context, final double boundaryStep) {
        double maxRangeStep = mRangeValueMin + boundaryStep;

        if (mLastUnit == HistoryUnitType.HUMIDITY) {

            final int minHumidityValue = context.getResources().getInteger(R.integer.history_graph_min_humidity_range);
            final int maxHumidityValue = context.getResources().getInteger(R.integer.history_graph_max_humidity_range);
            final int minSeparation = context.getResources().getInteger(R.integer.history_graph_min_separation);

            if (maxRangeStep < minHumidityValue + minSeparation) {
                return minHumidityValue + minSeparation;
            }
            if (maxRangeStep > maxHumidityValue) {
                return maxHumidityValue;
            }
        }
        return maxRangeStep;
    }

    private double getMinimumBoundariesStep(@NonNull final Context context) {
        final int rangeLabels = context.getResources().getInteger(R.integer.history_graph_view_num_default_range_labels);
        final float minimumGraphResolution = mLastUnit.getMinimumGraphResolution(context);
        return (rangeLabels * minimumGraphResolution) / 2;
    }

    /**
     * Updates the domain boundary.
     *
     * @param lastBoundary that it's going to be put in the
     */
    private void adjustDomainBoundaries(long lastBoundary) {
        if (lastBoundary < System.currentTimeMillis() - THRESHOLD_TIME_REPRESENTATION_MS) {
            lastBoundary = System.currentTimeMillis();
        }
        final long firstBoundary = lastBoundary - mLastInterval.getNumberMilliseconds();
        Log.d(TAG, String.format("adjustDomainBoundaries() -> Domain boundaries has been updated from %d to %d.", firstBoundary, lastBoundary));

        mViewPlot.setDomainBoundaries(firstBoundary, lastBoundary, BoundaryMode.FIXED);
    }
}