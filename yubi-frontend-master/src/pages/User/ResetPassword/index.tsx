import Footer from '@/components/Footer';
import {
  requestPasswordResetCodeUsingPOST,
  resetPasswordUsingPOST,
} from '@/services/yubi/userController';
import { Link } from '@@/exports';
import { LockOutlined, SafetyOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { useEmotionCss } from '@ant-design/use-emotion-css';
import { Helmet, history } from '@umijs/max';
import { Button, Form, message, Space } from 'antd';
import React, { useEffect, useState } from 'react';
import Settings from '../../../../config/defaultSettings';

const ResetPassword: React.FC = () => {
  const [form] = Form.useForm<API.UserResetPasswordRequest>();
  const [sendingCode, setSendingCode] = useState(false);
  const [cooldown, setCooldown] = useState(0);

  const containerClassName = useEmotionCss(() => {
    return {
      display: 'flex',
      flexDirection: 'column',
      height: '100vh',
      overflow: 'auto',
      backgroundImage:
        "url('https://mdn.alipayobjects.com/yuyan_qk0oxh/afts/img/V-_oS6r-i7wAAAAAAAAAAAAAFl94AQBr')",
      backgroundSize: '100% 100%',
    };
  });

  useEffect(() => {
    if (cooldown <= 0) return undefined;
    const timer = window.setInterval(() => {
      setCooldown((prev) => (prev <= 1 ? 0 : prev - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [cooldown]);

  const handleSendCode = async () => {
    const userAccount = (form.getFieldValue('userAccount') || '').trim();
    if (userAccount.length < 4) {
      message.warning('请先输入至少 4 位用户账号');
      return;
    }
    setSendingCode(true);
    try {
      const res = await requestPasswordResetCodeUsingPOST({ userAccount });
      if (res.code === 0) {
        setCooldown(60);
        if (res.data) {
          form.setFieldValue('resetCode', res.data);
          message.success(`验证码已生成（开发模式）：${res.data}，5 分钟内有效`);
        } else {
          message.success('重置验证码已生成，请在后端日志或联系管理员获取（5 分钟内有效）');
        }
      } else {
        message.error(res.message || '获取验证码失败');
      }
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '获取验证码失败，请重试');
    } finally {
      setSendingCode(false);
    }
  };

  const handleSubmit = async (values: API.UserResetPasswordRequest) => {
    if (values.newPassword !== values.checkPassword) {
      message.error('两次输入的新密码不一致');
      return;
    }
    try {
      const res = await resetPasswordUsingPOST(values);
      if (res.code === 0 && res.data) {
        message.success('密码重置成功，请使用新密码登录');
        history.push('/user/login');
        return;
      }
      message.error(res.message || '密码重置失败');
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '密码重置失败，请重试');
    }
  };

  return (
    <div className={containerClassName}>
      <Helmet>
        <title>
          {'重置密码'} - {Settings.title}
        </title>
      </Helmet>
      <div style={{ flex: '1', padding: '32px 0' }}>
        <LoginForm<API.UserResetPasswordRequest>
          form={form}
          contentStyle={{
            minWidth: 280,
            maxWidth: '75vw',
          }}
          logo={<img alt="logo" src="/logo.svg" />}
          title="Epiph-BI"
          subTitle="忘记密码 · 一次性验证码重置"
          submitter={{
            searchConfig: {
              submitText: '重置密码',
            },
          }}
          onFinish={handleSubmit}
        >
          <ProFormText
            name="userAccount"
            fieldProps={{
              size: 'large',
              prefix: <UserOutlined />,
            }}
            placeholder="请输入需要重置的账号（至少 4 位）"
            rules={[
              { required: true, message: '请输入用户账号' },
              { min: 4, message: '账号长度不能少于 4 位' },
            ]}
          />
          <Space.Compact style={{ width: '100%', marginBottom: 24 }}>
            <div style={{ flex: 1 }}>
              <ProFormText
                name="resetCode"
                noStyle
                fieldProps={{
                  size: 'large',
                  prefix: <SafetyOutlined />,
                  maxLength: 6,
                }}
                placeholder="请输入 6 位重置验证码"
                rules={[{ required: true, message: '请输入重置验证码' }]}
              />
            </div>
            <Button
              size="large"
              loading={sendingCode}
              disabled={cooldown > 0}
              onClick={handleSendCode}
            >
              {cooldown > 0 ? `${cooldown}s 后重试` : '获取验证码'}
            </Button>
          </Space.Compact>
          <ProFormText.Password
            name="newPassword"
            fieldProps={{
              size: 'large',
              prefix: <LockOutlined />,
            }}
            placeholder="请输入新密码（至少 8 位）"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 8, message: '新密码长度不能少于 8 位' },
            ]}
          />
          <ProFormText.Password
            name="checkPassword"
            fieldProps={{
              size: 'large',
              prefix: <LockOutlined />,
            }}
            placeholder="请再次确认新密码"
            rules={[
              { required: true, message: '请确认新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) {
                    return Promise.resolve();
                  }
                  return Promise.reject(new Error('两次输入的新密码不一致'));
                },
              }),
            ]}
          />
          <div style={{ marginBottom: 24 }}>
            <Link to="/user/login">返回登录</Link>
          </div>
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};

export default ResetPassword;
