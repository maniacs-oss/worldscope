const m = require('mithril');
const App = require('../app');

const User = module.exports = function (data) {
  this.id = m.prop(data.userId);
  this.username = m.prop(data.username);
  this.platform = m.prop(data.platform_type);
  this.alias = m.prop(data.alias);
  this.description = m.prop(data.description);
  this.email = m.prop(data.email);
};

User.get = () =>
    App.request({
      method: 'GET',
      url: 'src/js/modules/mockdata/user.json',
      type: User
    });

User.list = () =>
    App.request({
      method: 'GET',
      url: 'src/js/modules/mockdata/users.json',
      type: User
    });

User.update = (user) =>
    App.request({
      method: 'PUT',
      url: '/users/' + user.id(),
      data: {
        alias: user.alias(),
        description: user.description(),
        email: user.email()
      }
    });
