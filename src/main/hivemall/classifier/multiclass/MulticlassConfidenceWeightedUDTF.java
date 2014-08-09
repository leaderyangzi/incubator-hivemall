/*
 * Hivemall: Hive scalable Machine Learning Library
 *
 * Copyright (C) 2013
 *   National Institute of Advanced Industrial Science and Technology (AIST)
 *   Registration Number: H25PRO-1520
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package hivemall.classifier.multiclass;

import hivemall.common.FeatureValue;
import hivemall.common.Margin;
import hivemall.common.PredictionModel;
import hivemall.common.WeightValue;
import hivemall.common.WeightValue.WeightValueWithCovar;
import hivemall.utils.math.StatsUtils;

import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;

/**
 * A multi-class confidence-weighted linear classification.
 * <pre>
 * [1] Mark Dredze, Koby Crammer and Fernando Pereira. "Confidence-weighted linear classification",
 *     In Proc. ICML, pp.264-271, 2008.
 * [2] Koby Crammer, Mark Dredze and Alex Kulesza. "Multi-class confidence weighted algorithms",
 *     In Proc. EMNLP, Vol. 2, pp.496-504, 2008.
 * </pre>
 * 
 * @link http://dl.acm.org/citation.cfm?id=1390190
 * @link http://dl.acm.org/citation.cfm?id=1699577
 */
public class MulticlassConfidenceWeightedUDTF extends MulticlassOnlineClassifierUDTF {

    /** confidence parameter phi */
    protected float phi;

    @Override
    public StructObjectInspector initialize(ObjectInspector[] argOIs) throws UDFArgumentException {
        final int numArgs = argOIs.length;
        if(numArgs != 2 && numArgs != 3) {
            throw new UDFArgumentException("MultiClassConfidenceWeightedUDTF takes 2 or 3 arguments: List<String|Int|BitInt> features, {Int|String} label [, constant String options]");
        }

        return super.initialize(argOIs);
    }

    @Override
    protected boolean useCovariance() {
        return true;
    }

    @Override
    protected Options getOptions() {
        Options opts = super.getOptions();
        opts.addOption("phi", "confidence", true, "Confidence parameter [default 1.0]");
        opts.addOption("eta", "hyper_c", true, "Confidence hyperparameter eta in range (0.5, 1] [default 0.85]");
        return opts;
    }

    @Override
    protected CommandLine processOptions(ObjectInspector[] argOIs) throws UDFArgumentException {
        final CommandLine cl = super.processOptions(argOIs);

        float phi = 1.f;
        if(cl != null) {
            String phi_str = cl.getOptionValue("phi");
            if(phi_str == null) {
                String eta_str = cl.getOptionValue("eta");
                if(eta_str != null) {
                    double eta = Double.parseDouble(eta_str);
                    if(eta <= 0.5 || eta > 1) {
                        throw new UDFArgumentException("Confidence hyperparameter eta must be in range (0.5, 1]: "
                                + eta_str);
                    }
                    phi = (float) StatsUtils.probit(eta, 5d);
                }
            } else {
                phi = Float.parseFloat(phi_str);
            }
        }

        this.phi = phi;
        return cl;
    }

    @Override
    protected void train(List<?> features, Object actual_label) {
        Margin margin = getMarginAndVariance(features, actual_label, true);
        float gamma = getGamma(margin);

        if(gamma > 0.f) {// alpha = max(0, gamma)                   
            Object missed_label = margin.getMaxIncorrectLabel();
            update(features, gamma, actual_label, missed_label);
        }
    }

    protected final float getGamma(Margin margin) {
        float m = margin.get();
        float var = margin.getVariance();
        assert (var != 0);

        float b = 1.f + 2.f * phi * m;
        float gamma_numer = -b + (float) Math.sqrt(b * b - 8.f * phi * (m - phi * var));
        float gamma_denom = 4.f * phi * var;
        if(gamma_denom == 0.f) {// avoid divide-by-zero
            return 0.f;
        }
        return gamma_numer / gamma_denom;
    }

    protected void update(List<?> features, float alpha, Object actual_label, Object missed_label) {
        assert (actual_label != null);
        if(actual_label.equals(missed_label)) {
            throw new IllegalArgumentException("Actual label equals to missed label: "
                    + actual_label);
        }

        PredictionModel model2add = label2model.get(actual_label);
        if(model2add == null) {
            model2add = createModel();
            label2model.put(actual_label, model2add);
        }
        PredictionModel model2sub = null;
        if(missed_label != null) {
            model2sub = label2model.get(missed_label);
            if(model2sub == null) {
                model2sub = createModel();
                label2model.put(missed_label, model2sub);
            }
        }

        final ObjectInspector featureInspector = featureListOI.getListElementObjectInspector();
        for(Object f : features) {// w[f] += y * x[f]
            if(f == null) {
                continue;
            }
            final Object k;
            final float v;
            if(parseFeature) {
                FeatureValue fv = FeatureValue.parse(f);
                k = fv.getFeature();
                v = fv.getValue();
            } else {
                k = ObjectInspectorUtils.copyToStandardObject(f, featureInspector);
                v = 1.f;
            }
            WeightValue old_correctclass_w = model2add.get(k);
            WeightValue new_correctclass_w = getNewWeight(old_correctclass_w, v, alpha, phi, true);
            model2add.set(k, new_correctclass_w);

            if(model2sub != null) {
                WeightValue old_wrongclass_w = model2sub.get(k);
                WeightValue new_wrongclass_w = getNewWeight(old_wrongclass_w, v, alpha, phi, false);
                model2sub.set(k, new_wrongclass_w);
            }
        }
    }

    private static WeightValue getNewWeight(final WeightValue old, final float x, final float alpha, final float phi, final boolean positive) {
        final float old_w, old_cov;
        if(old == null) {
            old_w = 0.f;
            old_cov = 1.f;
        } else {
            old_w = old.get();
            old_cov = old.getCovariance();
        }

        float delta_w = alpha * old_cov * x;
        float new_w = positive ? old_w + delta_w : old_w - delta_w;
        float new_cov = 1.f / (1.f / old_cov + (2.f * alpha * phi * x * x));
        return new WeightValueWithCovar(new_w, new_cov);
    }

}
