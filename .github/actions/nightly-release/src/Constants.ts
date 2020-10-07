export default class Constants {
    public static get INVISIBLE_BODY_PREAMBLE(): string {
        return "<!-- Automatically created nightly release: "
            + "This release can safely be deleted by the nightly-release action -->";
    }
}
