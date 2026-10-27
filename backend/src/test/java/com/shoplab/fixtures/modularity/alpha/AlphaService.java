package com.shoplab.fixtures.modularity.alpha;

import com.shoplab.fixtures.modularity.beta.internal.Hidden;

/** Fixture: module alpha uses an internal of module beta (the violation Modulith must report). */
public class AlphaService {

    public Hidden hidden() {
        return new Hidden();
    }
}
