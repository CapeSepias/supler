class SuplerForm {
    private i18n: I18n;
    private validatorFnFactories: any;
    private validation: Validation;
    private validatorRenderOptions: ValidatorRenderOptions;
    private refreshControllerOptions: RefreshControllerOptions;
    private elementSearch: ElementSearch;
    private renderOptionsGetter: RenderOptionsGetter;

    constructor(private container: HTMLElement, customOptions: any) {
        // TODO: we shouldn't copy everything everywhere

        this.i18n = new I18n();
        Util.copyProperties(this.i18n, customOptions);

        var renderOptions = new DefaultRenderOptions();
        Util.copyProperties(renderOptions, customOptions);
        this.renderOptionsGetter = new HTMLRenderTemplateParser(this.container).parse(renderOptions);

        this.validatorFnFactories = new DefaultValidatorFnFactories(this.i18n);
        Util.copyProperties(this.validatorFnFactories, customOptions);

        this.validatorRenderOptions = new ValidatorRenderOptions;
        Util.copyProperties(this.validatorRenderOptions, customOptions);

        this.refreshControllerOptions = new RefreshControllerOptions(customOptions);

        this.elementSearch = new ElementSearch(container);
    }

    render(json) {
        var result = new CreateFormFromJson(this.renderOptionsGetter, this.i18n, this.validatorFnFactories).renderForm(json.main_form);
        this.container.innerHTML = result.html;
        this.validation = new Validation(this.elementSearch, result.elementDictionary,
            this.validatorRenderOptions, this.i18n);

        new RefreshController(this, result.elementDictionary, this.refreshControllerOptions, this.elementSearch,
            this.validation).attachRefreshListeners();

        this.validation.processServer(json.errors);

        this.refreshControllerOptions.afterRenderFunction()
    }

    getValue() {
        return ReadFormValues.getValueFrom(this.container);
    }

    /**
     * @returns True if there were validation errors.
     */
    validate(): boolean {
        return this.validation.processClient();
    }
}
