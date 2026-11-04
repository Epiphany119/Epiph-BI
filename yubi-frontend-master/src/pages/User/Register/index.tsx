import Footer from '@/components/Footer';
import { userRegisterUsingPOST } from '@/services/yubi/userController';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { Helmet, history, Link } from '@umijs/max';
import { message } from 'antd';
import React from 'react';
import Settings from '../../../../config/defaultSettings';

const Register: React.FC = () => {
  const handleSubmit = async (values: API.UserRegisterRequest) => {
    try {
      const res = await userRegisterUsingPOST(values);
      if (res.code === 0) {
        message.success('注册成功，请登录');
        history.push('/user/login');
        return;
      }
      message.error(res.message || '注册失败');
    } catch (error) {
      console.error(error);
      message.error('注册失败，请检查后端服务是否正常');
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <Helmet>
        <title>注册 - {Settings.title}</title>
      </Helmet>
      <div style={{ flex: 1, padding: '32px 0' }}>
        <LoginForm
          contentStyle={{ minWidth: 280, maxWidth: '75vw' }}
          logo={<img alt="logo" src="/logo.svg" />}
          title="Epiph-BI"
          subTitle="创建你的数据分析账户"
          onFinish={async (values) => handleSubmit(values as API.UserRegisterRequest)}
          submitter={{ searchConfig: { submitText: '注册' } }}
        >
          <ProFormText
            name="userAccount"
            fieldProps={{ size: 'large', prefix: <UserOutlined /> }}
            placeholder="请输入账号（至少 4 位）"
            rules={[{ required: true, min: 4, message: '账号至少需要 4 位！' }]}
          />
          <ProFormText.Password
            name="userPassword"
            fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
            placeholder="请输入密码（至少 8 位）"
            rules={[{ required: true, min: 8, message: '密码至少需要 8 位！' }]}
          />
          <ProFormText.Password
            name="checkPassword"
            fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
            placeholder="请再次输入密码"
            dependencies={['userPassword']}
            rules={[
              { required: true, message: '请再次输入密码！' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('userPassword') === value) return Promise.resolve();
                  return Promise.reject(new Error('两次输入的密码不一致！'));
                },
              }),
            ]}
          />
          <div style={{ marginTop: 8, textAlign: 'center' }}>
            已有账号？<Link to="/user/login">返回登录</Link>
          </div>
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};

export default Register;
