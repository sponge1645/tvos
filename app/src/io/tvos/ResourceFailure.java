package io.tvos;

import java.io.IOException;

/** Known application failure: keeps a resource identity independently of the displayed language. */
final class ResourceFailure extends IOException {
    private static final long serialVersionUID = 1L;
    final int resource;
    final String detail;
    ResourceFailure(int resource) {
        this(resource,null);
    }
    ResourceFailure(int resource,String detail) {
        super(AppStrings.get(resource)+(detail==null?"":" ("+detail+")"));
        this.resource = resource;
        this.detail = detail;
    }
}
