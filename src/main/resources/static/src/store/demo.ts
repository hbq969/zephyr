import {defineStore} from 'pinia'

export const demoStore = defineStore('demo', {
    state: () => ({
        foo: 'bar'
    }),
    actions: {
        setFoo(foo: string) {
            this.foo = foo
        },
        getFoo() {
            return this.foo
        }
    }
})